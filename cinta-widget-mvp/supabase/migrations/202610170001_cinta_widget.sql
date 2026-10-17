-- Cinta Widget: secure two-member pairing and independent member photos.
-- Apply in Supabase SQL Editor or with Supabase CLI. No service_role key is used by the app.

create extension if not exists pgcrypto with schema extensions;

create table if not exists public.couples (
    id uuid primary key default gen_random_uuid(),
    created_by uuid not null references auth.users(id) on delete cascade,
    status text not null default 'active' check (status in ('active', 'dissolved')),
    created_at timestamptz not null default now(),
    dissolved_at timestamptz
);

create table if not exists public.couple_members (
    couple_id uuid not null references public.couples(id) on delete cascade,
    user_id uuid not null references auth.users(id) on delete cascade,
    joined_at timestamptz not null default now(),
    left_at timestamptz,
    primary key (couple_id, user_id)
);

-- One active couple per account; historical memberships may remain after left_at is set.
create unique index if not exists couple_members_one_active_couple_per_user
    on public.couple_members(user_id) where left_at is null;
create index if not exists couple_members_by_user on public.couple_members(user_id);

create table if not exists public.pairing_codes (
    id uuid primary key default gen_random_uuid(),
    couple_id uuid not null references public.couples(id) on delete cascade,
    created_by uuid not null references auth.users(id) on delete cascade,
    code_hash text not null unique check (code_hash ~ '^[0-9a-f]{64}$'),
    created_at timestamptz not null default now(),
    expires_at timestamptz not null,
    used_by uuid references auth.users(id) on delete set null,
    used_at timestamptz,
    attempts integer not null default 0 check (attempts >= 0)
);

-- Persists failed-code rate limits. No client role can directly read or mutate this table.
create table if not exists public.pairing_attempts (
    user_id uuid primary key references auth.users(id) on delete cascade,
    window_started_at timestamptz not null default now(),
    attempts integer not null default 0 check (attempts >= 0)
);

create table if not exists public.member_photos (
    photo_id uuid primary key default gen_random_uuid(),
    couple_id uuid not null,
    owner_uid uuid not null,
    object_path text not null unique,
    caption text check (caption is null or char_length(caption) <= 100),
    version integer not null default 1 check (version > 0),
    content_hash text not null check (content_hash ~ '^[0-9a-f]{64}$'),
    updated_at timestamptz not null default now(),
    foreign key (couple_id, owner_uid)
        references public.couple_members(couple_id, user_id) on delete cascade
);
create unique index if not exists member_photos_one_per_owner on public.member_photos(couple_id, owner_uid);
create index if not exists member_photos_by_owner on public.member_photos(owner_uid, version);

alter table public.couples enable row level security;
alter table public.couple_members enable row level security;
alter table public.pairing_codes enable row level security;
alter table public.pairing_attempts enable row level security;
alter table public.member_photos enable row level security;

-- SECURITY DEFINER avoids recursive RLS evaluation when a policy needs to test membership.
create or replace function public.is_active_couple_member(p_couple_id uuid)
returns boolean
language sql
stable
security definer
set search_path = public, pg_temp
as $$
    select exists (
        select 1
        from public.couple_members cm
        join public.couples c on c.id = cm.couple_id
        where cm.couple_id = p_couple_id
          and cm.user_id = auth.uid()
          and cm.left_at is null
          and c.status = 'active'
    );
$$;
revoke all on function public.is_active_couple_member(uuid) from public, anon;
grant execute on function public.is_active_couple_member(uuid) to authenticated;

-- Parse object paths inside PL/pgSQL so a malformed path cannot force a UUID cast error in RLS.
create or replace function public.is_active_couple_member_path(p_object_name text)
returns boolean
language plpgsql
stable
security definer
set search_path = public, pg_temp
as $$
declare
    v_couple_prefix text;
begin
    if p_object_name is null or p_object_name !~ '^[0-9a-f-]{36}/[0-9a-f-]{36}/[0-9a-f-]{36}\.webp$' then
        return false;
    end if;
    v_couple_prefix := split_part(p_object_name, '/', 1);
    return public.is_active_couple_member(v_couple_prefix::uuid);
exception when invalid_text_representation then
    return false;
end;
$$;
revoke all on function public.is_active_couple_member_path(text) from public, anon;
grant execute on function public.is_active_couple_member_path(text) to authenticated;

-- Read access only to the currently active couple. Pairing writes go through locked RPCs only.
drop policy if exists couples_read_active_member on public.couples;
create policy couples_read_active_member on public.couples
for select to authenticated using (
    status = 'active' and exists (
        select 1 from public.couple_members cm
        where cm.couple_id = couples.id and cm.user_id = auth.uid() and cm.left_at is null
    )
);

drop policy if exists couple_members_read_own_active_couple on public.couple_members;
create policy couple_members_read_own_active_couple on public.couple_members
for select to authenticated using (public.is_active_couple_member(couple_id));

drop policy if exists member_photos_read_active_couple on public.member_photos;
create policy member_photos_read_active_couple on public.member_photos
for select to authenticated using (public.is_active_couple_member(couple_id));

-- No direct client mutations of membership, invitations, attempts, or photo metadata.
revoke all on public.couples, public.couple_members, public.pairing_codes,
    public.pairing_attempts, public.member_photos from anon, authenticated;
grant select on public.couples, public.couple_members, public.member_photos to authenticated;

create or replace function public.create_couple()
returns uuid
language plpgsql
security definer
set search_path = public, extensions, pg_temp
as $$
declare
    v_uid uuid := auth.uid();
    v_couple_id uuid;
begin
    if v_uid is null then raise exception 'authentication required'; end if;
    if exists (select 1 from public.couple_members where user_id = v_uid and left_at is null) then
        raise exception 'account already belongs to an active couple';
    end if;
    insert into public.couples(created_by) values (v_uid) returning id into v_couple_id;
    insert into public.couple_members(couple_id, user_id) values (v_couple_id, v_uid);
    return v_couple_id;
end;
$$;

create or replace function public.create_pairing_code(p_code_hash text)
returns timestamptz
language plpgsql
security definer
set search_path = public, extensions, pg_temp
as $$
declare
    v_uid uuid := auth.uid();
    v_couple_id uuid;
    v_count integer;
    v_expires timestamptz := now() + interval '10 minutes';
begin
    if v_uid is null then raise exception 'authentication required'; end if;
    if p_code_hash is null or p_code_hash !~ '^[0-9a-f]{64}$' then
        raise exception 'invalid invitation hash';
    end if;

    select c.id into v_couple_id
    from public.couples c
    join public.couple_members cm on cm.couple_id = c.id
    where cm.user_id = v_uid and cm.left_at is null and c.status = 'active'
    for update of c;
    if v_couple_id is null then raise exception 'create a couple before inviting'; end if;

    select count(*) into v_count from public.couple_members
    where couple_id = v_couple_id and left_at is null;
    if v_count <> 1 then raise exception 'pair is already full'; end if;

    delete from public.pairing_codes where created_by = v_uid and used_at is null;
    insert into public.pairing_codes(couple_id, created_by, code_hash, expires_at)
    values (v_couple_id, v_uid, p_code_hash, v_expires);
    return v_expires;
end;
$$;

create or replace function public.redeem_pairing_code(p_code text)
returns uuid
language plpgsql
security definer
set search_path = public, extensions, pg_temp
as $$
declare
    v_uid uuid := auth.uid();
    v_hash text;
    v_attempts integer;
    v_couple_id uuid;
    v_status text;
    v_invite public.pairing_codes%rowtype;
    v_count integer;
begin
    if v_uid is null then raise exception 'authentication required'; end if;

    insert into public.pairing_attempts(user_id, window_started_at, attempts)
    values (v_uid, now(), 1)
    on conflict (user_id) do update set
        attempts = case
            when pairing_attempts.window_started_at < now() - interval '1 hour' then 1
            else pairing_attempts.attempts + 1
        end,
        window_started_at = case
            when pairing_attempts.window_started_at < now() - interval '1 hour' then now()
            else pairing_attempts.window_started_at
        end
    returning attempts into v_attempts;

    -- Return NULL instead of raising on expected invalid-code cases so the rate-limit update commits.
    if v_attempts > 10 or p_code is null or length(p_code) < 16 or length(p_code) > 64 then
        return null;
    end if;
    v_hash := encode(extensions.digest(p_code, 'sha256'), 'hex');

    select couple_id into v_couple_id from public.pairing_codes where code_hash = v_hash;
    if v_couple_id is null then return null; end if;

    -- Lock order is couple then invitation, matching create_pairing_code and preventing double redemption.
    select status into v_status from public.couples where id = v_couple_id for update;
    if v_status is distinct from 'active' then return null; end if;
    select * into v_invite from public.pairing_codes where code_hash = v_hash for update;
    if not found or v_invite.used_at is not null or v_invite.expires_at <= now() then
        return null;
    end if;
    if v_invite.created_by = v_uid then return null; end if;
    if exists (select 1 from public.couple_members where user_id = v_uid and left_at is null) then
        return null;
    end if;
    if not exists (select 1 from public.couple_members
                   where couple_id = v_couple_id and user_id = v_invite.created_by and left_at is null) then
        return null;
    end if;
    select count(*) into v_count from public.couple_members
    where couple_id = v_couple_id and left_at is null;
    if v_count <> 1 then return null; end if;

    insert into public.couple_members(couple_id, user_id) values (v_couple_id, v_uid);
    update public.pairing_codes set used_by = v_uid, used_at = now(), attempts = attempts + 1
    where id = v_invite.id;
    return v_couple_id;
end;
$$;

-- Upload first, then atomically publish metadata only if the owner's expected version is current.
-- A version conflict leaves the old row untouched; client must keep the queued file and ask user to retry.
create or replace function public.publish_member_photo(
    p_couple_id uuid,
    p_object_path text,
    p_caption text,
    p_content_hash text,
    p_expected_version integer
)
returns setof public.member_photos
language plpgsql
security definer
set search_path = public, extensions, pg_temp
as $$
declare
    v_uid uuid := auth.uid();
    v_status text;
begin
    if v_uid is null then raise exception 'authentication required'; end if;
    if p_expected_version is null or p_expected_version < 0 then raise exception 'invalid expected version'; end if;
    if p_caption is not null and char_length(p_caption) > 100 then raise exception 'caption too long'; end if;
    if p_content_hash is null or p_content_hash !~ '^[0-9a-f]{64}$' then raise exception 'invalid content hash'; end if;
    if p_object_path is null or p_object_path !~ ('^' || p_couple_id::text || '/' || v_uid::text || '/[0-9a-f-]{36}\.webp$') then
        raise exception 'photo path must belong to the signed-in owner';
    end if;
    -- Do not publish metadata for a fabricated or failed upload. The bucket independently caps
    -- object size and MIME type; Storage RLS restricts creation to this owner folder.
    if not exists (
        select 1 from storage.objects o
        where o.bucket_id = 'couple-photos'
          and o.name = p_object_path
          and o.owner_id = v_uid::text
    ) then
        raise exception 'uploaded photo object required';
    end if;
    select c.status into v_status from public.couples c
    join public.couple_members cm on cm.couple_id = c.id
    where c.id = p_couple_id and cm.user_id = v_uid and cm.left_at is null
    for update of c;
    if v_status is distinct from 'active' then raise exception 'active couple membership required'; end if;

    return query
    insert into public.member_photos as existing
        (couple_id, owner_uid, object_path, caption, version, content_hash, updated_at)
    values (p_couple_id, v_uid, p_object_path, nullif(trim(p_caption), ''), 1, p_content_hash, now())
    on conflict (couple_id, owner_uid) do update set
        object_path = excluded.object_path,
        caption = excluded.caption,
        version = existing.version + 1,
        content_hash = excluded.content_hash,
        updated_at = now()
    where existing.version = p_expected_version
    returning existing.*;

    if not found then raise exception 'PHOTO_VERSION_CONFLICT'; end if;
end;
$$;

-- Ending a couple revokes access immediately. Client should best-effort delete its own Storage object
-- before calling this RPC; any offline device clears its local partner cache on the next allowed sync.
create or replace function public.leave_couple()
returns uuid
language plpgsql
security definer
set search_path = public, extensions, pg_temp
as $$
declare
    v_uid uuid := auth.uid();
    v_couple_id uuid;
begin
    if v_uid is null then raise exception 'authentication required'; end if;
    select c.id into v_couple_id from public.couples c
    join public.couple_members cm on cm.couple_id = c.id
    where cm.user_id = v_uid and cm.left_at is null and c.status = 'active'
    for update of c;
    if v_couple_id is null then return null; end if;

    delete from public.member_photos where couple_id = v_couple_id;
    update public.pairing_codes set used_at = coalesce(used_at, now())
      where couple_id = v_couple_id and used_at is null;
    update public.couple_members set left_at = now()
      where couple_id = v_couple_id and left_at is null;
    update public.couples set status = 'dissolved', dissolved_at = now() where id = v_couple_id;
    return v_couple_id;
end;
$$;

revoke all on function public.create_couple() from public, anon;
revoke all on function public.create_pairing_code(text) from public, anon;
revoke all on function public.redeem_pairing_code(text) from public, anon;
revoke all on function public.publish_member_photo(uuid, text, text, text, integer) from public, anon;
revoke all on function public.leave_couple() from public, anon;
grant execute on function public.create_couple() to authenticated;
grant execute on function public.create_pairing_code(text) to authenticated;
grant execute on function public.redeem_pairing_code(text) to authenticated;
grant execute on function public.publish_member_photo(uuid, text, text, text, integer) to authenticated;
grant execute on function public.leave_couple() to authenticated;

-- Private bucket, limited to 512 KiB and WebP only. Uploads always use new UUID paths (no upsert).
insert into storage.buckets(id, name, public, file_size_limit, allowed_mime_types)
values ('couple-photos', 'couple-photos', false, 524288, array['image/webp'])
on conflict (id) do update set
    public = false,
    file_size_limit = 524288,
    allowed_mime_types = array['image/webp'];

drop policy if exists cinta_photo_select_member on storage.objects;
create policy cinta_photo_select_member on storage.objects
for select to authenticated using (
    bucket_id = 'couple-photos'
    and name ~ '^[0-9a-f-]{36}/[0-9a-f-]{36}/[0-9a-f-]{36}\.webp$'
    and public.is_active_couple_member_path(storage.objects.name)
);

drop policy if exists cinta_photo_insert_owner on storage.objects;
create policy cinta_photo_insert_owner on storage.objects
for insert to authenticated with check (
    bucket_id = 'couple-photos'
    and name ~ ('^[0-9a-f-]{36}/' || auth.uid()::text || '/[0-9a-f-]{36}\.webp$')
    and public.is_active_couple_member_path(storage.objects.name)
);

drop policy if exists cinta_photo_delete_owner on storage.objects;
create policy cinta_photo_delete_owner on storage.objects
for delete to authenticated using (
    bucket_id = 'couple-photos'
    and split_part(name, '/', 2) = auth.uid()::text
    and public.is_active_couple_member_path(storage.objects.name)
);

-- Supabase Realtime must publish member_photos for peer-photo changes to be delivered.
alter table public.member_photos replica identity full;
do $$
begin
    if exists (select 1 from pg_publication where pubname = 'supabase_realtime')
       and not exists (
           select 1 from pg_publication_tables
           where pubname = 'supabase_realtime' and schemaname = 'public' and tablename = 'member_photos'
       ) then
        alter publication supabase_realtime add table public.member_photos;
    end if;
end $$;
