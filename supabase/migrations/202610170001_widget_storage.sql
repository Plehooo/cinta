-- Galeri Cinta: private bucket for small, compressed images used by the shared home-screen widget.
-- Before running: replace YOUR_FIREBASE_PROJECT_ID with the Firebase project ID used by the Android app.
-- Then configure Supabase Dashboard > Authentication > Third-Party Auth > Firebase with that same project ID.
-- This bucket stays PRIVATE. The app shares a time-limited signed URL through couple-protected Firestore metadata.

insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values ('galeri-widget', 'galeri-widget', false, 950000, array['image/jpeg'])
on conflict (id) do update set
  public = false,
  file_size_limit = 950000,
  allowed_mime_types = array['image/jpeg'];

-- Firebase ID tokens without a custom `role` claim are evaluated as `anon` by Supabase.
-- Grant only the minimal object operations needed, then restrict them to the exact Firebase issuer/audience
-- and the current UID's own users/<uid>/<random>.jpg folder. Never add a service_role key to the APK.
grant select, insert, delete on table storage.objects to anon;

drop policy if exists "galeri_widget_select_own" on storage.objects;
create policy "galeri_widget_select_own"
on storage.objects for select to anon
using (
  bucket_id = 'galeri-widget'
  and (storage.foldername(name))[1] = 'users'
  and (storage.foldername(name))[2] = (select auth.jwt() ->> 'sub')
  and (select auth.jwt() ->> 'iss') = 'https://securetoken.google.com/YOUR_FIREBASE_PROJECT_ID'
  and (select auth.jwt() ->> 'aud') = 'YOUR_FIREBASE_PROJECT_ID'
);

drop policy if exists "galeri_widget_insert_own" on storage.objects;
create policy "galeri_widget_insert_own"
on storage.objects for insert to anon
with check (
  bucket_id = 'galeri-widget'
  and (storage.foldername(name))[1] = 'users'
  and (storage.foldername(name))[2] = (select auth.jwt() ->> 'sub')
  and (select auth.jwt() ->> 'iss') = 'https://securetoken.google.com/YOUR_FIREBASE_PROJECT_ID'
  and (select auth.jwt() ->> 'aud') = 'YOUR_FIREBASE_PROJECT_ID'
  and lower(storage.extension(name)) = 'jpg'
);

drop policy if exists "galeri_widget_delete_own" on storage.objects;
create policy "galeri_widget_delete_own"
on storage.objects for delete to anon
using (
  bucket_id = 'galeri-widget'
  and (storage.foldername(name))[1] = 'users'
  and (storage.foldername(name))[2] = (select auth.jwt() ->> 'sub')
  and (select auth.jwt() ->> 'iss') = 'https://securetoken.google.com/YOUR_FIREBASE_PROJECT_ID'
  and (select auth.jwt() ->> 'aud') = 'YOUR_FIREBASE_PROJECT_ID'
);


-- Defense in depth: restrictive policy prevents another permissive Storage policy from
-- accidentally opening this bucket. It leaves every other bucket's policy behavior unchanged.
drop policy if exists "galeri_widget_restrict_bucket_access" on storage.objects;
create policy "galeri_widget_restrict_bucket_access"
on storage.objects as restrictive for all to anon
using (
  bucket_id <> 'galeri-widget'
  or (
    bucket_id = 'galeri-widget'
    and (storage.foldername(name))[1] = 'users'
    and (storage.foldername(name))[2] = (select auth.jwt() ->> 'sub')
    and (select auth.jwt() ->> 'iss') = 'https://securetoken.google.com/YOUR_FIREBASE_PROJECT_ID'
    and (select auth.jwt() ->> 'aud') = 'YOUR_FIREBASE_PROJECT_ID'
  )
)
with check (
  bucket_id <> 'galeri-widget'
  or (
    bucket_id = 'galeri-widget'
    and (storage.foldername(name))[1] = 'users'
    and (storage.foldername(name))[2] = (select auth.jwt() ->> 'sub')
    and (select auth.jwt() ->> 'iss') = 'https://securetoken.google.com/YOUR_FIREBASE_PROJECT_ID'
    and (select auth.jwt() ->> 'aud') = 'YOUR_FIREBASE_PROJECT_ID'
    and lower(storage.extension(name)) = 'jpg'
  )
);
