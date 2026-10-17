#!/usr/bin/env python3
"""Fast source-level checks. These are not a substitute for running Android/DB integration tests."""
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]

def read(path):
    return (ROOT / path).read_text(encoding="utf-8")

checks = []
def check(name, condition):
    checks.append((name, bool(condition)))

routing = read("app/src/main/java/com/plehooo/cinta/PhotoRouting.kt")
repo = read("app/src/main/java/com/plehooo/cinta/PhotoSyncRepository.kt")
cache = read("app/src/main/java/com/plehooo/cinta/WidgetCache.kt")
queue = read("app/src/main/java/com/plehooo/cinta/PhotoQueue.kt")
widget = read("app/src/main/java/com/plehooo/cinta/CintaWidget.kt")
sql = read("supabase/migrations/202610170001_cinta_widget.sql")
gradle = read("app/build.gradle.kts")

check("routing selects the viewer's exact couple", "coupleMembers = active.filter { it.coupleId == mine.coupleId }" in routing)
check("routing fails closed unless exactly two active members", "if (coupleMembers.size != 2) return null" in routing)
check("widget route only permits partner owner", "publishedOwnerUid == route.partnerUid && publishedOwnerUid != route.viewerUid" in routing)
check("refresh filters photo to routed partner", "PhotoRouting.canAffectWidget(route, it.ownerUid)" in repo)
check("widget does not use a global currentPhoto field", "currentPhoto" not in widget + cache + repo)
check("own upload is stored in owner-specific queue", 'owner-$ownerUid.webp' in read("app/src/main/java/com/plehooo/cinta/PhotoFiles.kt"))
check("queue saves locally before any network work", "PhotoQueue.save(context, uid, bytes, metadata)" in repo and "No network call here" in repo)
check("Storage bucket is private with MIME and size restrictions", "('couple-photos', 'couple-photos', false, 524288, array['image/webp'])" in sql)
check("RLS enabled on all required tables", all(f"alter table public.{t} enable row level security" in sql for t in ["couples", "couple_members", "pairing_codes", "pairing_attempts", "member_photos"]))
check("metadata cannot be directly written by client roles", "revoke all on public.couples, public.couple_members, public.pairing_codes," in sql and "grant select on public.couples, public.couple_members, public.member_photos to authenticated" in sql)
check("pair invitation hashes and expiry are stored", "code_hash text not null unique" in sql and "now() + interval '10 minutes'" in sql)
check("pairing RPC rate-limits failed attempts", "if v_attempts > 10" in sql)
check("member-photo publishing validates uploaded object", "uploaded photo object required" in sql and "o.owner_id = v_uid::text" in sql)
check("backend rejects expected-version conflicts", "PHOTO_VERSION_CONFLICT" in sql and "where existing.version = p_expected_version" in sql)
code_text = "\n".join(
    "\n".join(line for line in p.read_text(errors="ignore").splitlines() if not line.lstrip().startswith(("#", "//", "--")))
    for p in ROOT.rglob("*")
    if p.is_file() and ".git" not in p.parts and p.suffix.lower() in {".kt", ".kts", ".sql", ".xml", ".yml", ".yaml"}
)
check("no service role or backend secret embedded in executable source", not re.search(r"SUPABASE_(?:SERVICE_ROLE|SECRET)_KEY\s*=|service_role\s*[:=]\s*[\"']", code_text, re.I))
check("Gradle only accepts client-safe runtime config inputs", "SUPABASE_PUBLISHABLE_KEY" in gradle and "SUPABASE_URL" in gradle)

failed = False
for name, ok in checks:
    print(("PASS" if ok else "FAIL") + " - " + name)
    failed |= not ok
print(f"{len(checks) - sum(not ok for _, ok in checks)}/{len(checks)} source checks passed")
sys.exit(1 if failed else 0)
