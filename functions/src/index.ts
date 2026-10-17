import { createHash, randomBytes } from "node:crypto";
import { initializeApp } from "firebase-admin/app";
import { getMessaging } from "firebase-admin/messaging";
import { getStorage } from "firebase-admin/storage";
import { FieldPath, FieldValue, Timestamp, getFirestore } from "firebase-admin/firestore";
import type { DocumentSnapshot, QueryDocumentSnapshot } from "firebase-admin/firestore";
import { HttpsError, onCall } from "firebase-functions/v2/https";
import { onDocumentCreated, onDocumentDeleted, onDocumentUpdated } from "firebase-functions/v2/firestore";
import * as logger from "firebase-functions/logger";

initializeApp();
const db = getFirestore();
const INVITE_TTL_MS = 15 * 60 * 1000;
const COOLDOWN_MS = 30 * 1000;
const REDEEM_WINDOW_MS = 15 * 60 * 1000;
const REDEEM_MAX_ATTEMPTS = 10;

function requireUid(uid: string | undefined): string {
  if (!uid) throw new HttpsError("unauthenticated", "Silakan login terlebih dahulu.");
  return uid;
}

function hashCode(code: string): string {
  return createHash("sha256").update(code.trim().toUpperCase(), "utf8").digest("hex");
}

function normalizeCode(value: unknown): string {
  if (typeof value !== "string") throw new HttpsError("invalid-argument", "Kode undangan wajib diisi.");
  const normalized = value.trim().toUpperCase();
  if (!/^[A-Z0-9-]{8,32}$/.test(normalized)) {
    throw new HttpsError("invalid-argument", "Format kode undangan tidak valid.");
  }
  return normalized;
}

async function reserveRedeemAttempt(uid: string): Promise<void> {
  const attemptRef = db.collection("pairingAttempts").doc(uid);
  const now = Date.now();
  await db.runTransaction(async (tx) => {
    const snapshot = await tx.get(attemptRef);
    const windowStart = snapshot.get("windowStartedAt") as Timestamp | undefined;
    const attempts = Number(snapshot.get("attempts") ?? 0);
    if (!windowStart || now - windowStart.toMillis() >= REDEEM_WINDOW_MS) {
      tx.set(attemptRef, {
        windowStartedAt: Timestamp.fromMillis(now),
        attempts: 1,
        updatedAt: FieldValue.serverTimestamp()
      });
      return;
    }
    if (attempts >= REDEEM_MAX_ATTEMPTS) {
      throw new HttpsError("resource-exhausted", "Terlalu banyak percobaan kode. Coba lagi setelah 15 menit.");
    }
    tx.update(attemptRef, { attempts: attempts + 1, updatedAt: FieldValue.serverTimestamp() });
  });
}

export const createInviteCode = onCall({ maxInstances: 10 }, async (request) => {
  const uid = requireUid(request.auth?.uid);
  const userRef = db.collection("users").doc(uid);
  const now = Date.now();

  const user = await userRef.get();
  if (!user.exists) throw new HttpsError("failed-precondition", "Profil belum siap. Coba buka ulang aplikasi.");
  if (user.get("coupleId")) throw new HttpsError("failed-precondition", "Akun ini sudah terhubung dengan pasangan.");
  const lastInviteAt = user.get("lastInviteAt") as Timestamp | undefined;
  if (lastInviteAt && now - lastInviteAt.toMillis() < COOLDOWN_MS) {
    throw new HttpsError("resource-exhausted", "Tunggu sebentar sebelum membuat kode baru.");
  }

  // 12 random bytes => 24 hexadecimal characters (96 bits). Only the hash is stored.
  const code = randomBytes(12).toString("hex").toUpperCase();
  const inviteRef = db.collection("pairingCodes").doc(hashCode(code));
  const expiresAt = Timestamp.fromMillis(now + INVITE_TTL_MS);
  await db.runTransaction(async (tx) => {
    const freshUser = await tx.get(userRef);
    if (!freshUser.exists || freshUser.get("coupleId")) {
      throw new HttpsError("failed-precondition", "Akun sudah berubah status. Muat ulang aplikasi.");
    }
    const lastInviteInTransaction = freshUser.get("lastInviteAt") as Timestamp | undefined;
    if (lastInviteInTransaction && now - lastInviteInTransaction.toMillis() < COOLDOWN_MS) {
      throw new HttpsError("resource-exhausted", "Tunggu sebentar sebelum membuat kode baru.");
    }
    tx.create(inviteRef, {
      ownerUid: uid,
      createdAt: FieldValue.serverTimestamp(),
      expiresAt,
      usedAt: null,
      usedBy: null
    });
    tx.update(userRef, { lastInviteAt: FieldValue.serverTimestamp(), updatedAt: FieldValue.serverTimestamp() });
  });
  return { code, expiresInSeconds: INVITE_TTL_MS / 1000 };
});

export const redeemInviteCode = onCall({ maxInstances: 10 }, async (request) => {
  const uid = requireUid(request.auth?.uid);
  await reserveRedeemAttempt(uid);
  const code = normalizeCode(request.data?.code);
  const inviteRef = db.collection("pairingCodes").doc(hashCode(code));
  const meRef = db.collection("users").doc(uid);
  const now = Date.now();

  const inviteSnap = await inviteRef.get();
  if (!inviteSnap.exists) throw new HttpsError("not-found", "Kode tidak ditemukan atau sudah tidak berlaku.");
  const ownerUid = inviteSnap.get("ownerUid") as string | undefined;
  if (!ownerUid || ownerUid === uid) throw new HttpsError("failed-precondition", "Kode milik sendiri tidak bisa digunakan.");
  const ownerRef = db.collection("users").doc(ownerUid);
  const coupleRef = db.collection("couples").doc();

  await db.runTransaction(async (tx) => {
    // All authorization and single-use checks happen atomically in this transaction.
    const [freshInvite, me, owner] = await Promise.all([
      tx.get(inviteRef), tx.get(meRef), tx.get(ownerRef)
    ]);
    if (!freshInvite.exists || !me.exists || !owner.exists) {
      throw new HttpsError("not-found", "Kode atau profil tidak ditemukan.");
    }
    const expiresAt = freshInvite.get("expiresAt") as Timestamp | undefined;
    if (!expiresAt || expiresAt.toMillis() <= now) {
      throw new HttpsError("deadline-exceeded", "Kode undangan sudah kedaluwarsa.");
    }
    if (freshInvite.get("usedAt")) throw new HttpsError("already-exists", "Kode undangan sudah digunakan.");
    if (freshInvite.get("ownerUid") !== ownerUid) throw new HttpsError("aborted", "Kode undangan berubah. Coba lagi.");
    if (me.get("coupleId") || owner.get("coupleId")) {
      throw new HttpsError("failed-precondition", "Salah satu akun sudah terhubung dengan pasangan lain.");
    }

    tx.create(coupleRef, {
      members: [ownerUid, uid],
      status: "active",
      createdAt: FieldValue.serverTimestamp(),
      createdBy: uid,
      schemaVersion: 1
    });
    tx.update(meRef, { coupleId: coupleRef.id, updatedAt: FieldValue.serverTimestamp() });
    tx.update(ownerRef, { coupleId: coupleRef.id, updatedAt: FieldValue.serverTimestamp() });
    tx.update(inviteRef, { usedAt: FieldValue.serverTimestamp(), usedBy: uid });
  });
  return { coupleId: coupleRef.id, paired: true };
});


async function notifyOtherMembers(
  coupleId: string,
  actorUid: string,
  title: string,
  body: string,
  eventType: string
): Promise<void> {
  const couple = await db.collection("couples").doc(coupleId).get();
  if (!couple.exists || couple.get("status") !== "active") return;
  const members = (couple.get("members") as unknown[] | undefined)?.filter((v): v is string => typeof v === "string") ?? [];
  const targets = members.filter((uid) => uid !== actorUid);
  for (const targetUid of targets) {
    const tokenSnap = await db.collection("users").doc(targetUid).collection("deviceTokens").get();
    const docs = tokenSnap.docs
      .map((doc) => ({ id: doc.id, token: doc.get("token") }))
      .filter((row): row is { id: string; token: string } => typeof row.token === "string" && row.token.length > 10);
    // FCM multicast has a 500-token limit; chunk devices to keep one bad account from breaking delivery.
    for (let offset = 0; offset < docs.length; offset += 500) {
      const chunk = docs.slice(offset, offset + 500);
      const response = await getMessaging().sendEachForMulticast({
        tokens: chunk.map((row) => row.token),
        notification: { title, body },
        data: { type: eventType },
        android: { priority: "normal" }
      });
      const invalidCodes = new Set([
        "messaging/invalid-registration-token",
        "messaging/registration-token-not-registered"
      ]);
      const batch = db.batch();
      let removals = 0;
      response.responses.forEach((item, index) => {
        if (!item.success && item.error && invalidCodes.has(item.error.code)) {
          batch.delete(db.collection("users").doc(targetUid).collection("deviceTokens").doc(chunk[index].id));
          removals++;
        }
      });
      if (removals > 0) await batch.commit();
    }
  }
}

export const notifySharedMedia = onDocumentCreated(
  "couples/{coupleId}/sharedMedia/{mediaId}",
  async (event) => {
    const media = event.data?.data();
    if (!media) return;
    await notifyOtherMembers(
      event.params.coupleId,
      typeof media.ownerUid === "string" ? media.ownerUid : "",
      "Kenangan baru dibagikan ♥",
      "Pasanganmu menambahkan media ke Galeri Bersama.",
      "shared_media_created"
    );
  }
);

async function findAccessibleMediaForPersonalSlot(
  uid: string,
  coupleId: string,
  mediaId: string
): Promise<DocumentSnapshot | undefined> {
  const privateMedia = await db.collection("users").doc(uid).collection("privateMedia").doc(mediaId).get();
  if (privateMedia.exists) return privateMedia;
  const sharedMedia = await db.collection("couples").doc(coupleId).collection("sharedMedia").doc(mediaId).get();
  return sharedMedia.exists ? sharedMedia : undefined;
}

export const cleanupDeletedSharedMediaSlots = onDocumentDeleted(
  { document: "couples/{coupleId}/sharedMedia/{mediaId}", retry: true },
  async (event) => {
    const { coupleId, mediaId } = event.params;
    const couple = await db.collection("couples").doc(coupleId).get();
    if (!couple.exists || couple.get("status") !== "active") return;
    const actorUid = typeof event.data?.get("ownerUid") === "string" ? event.data.get("ownerUid") as string : "";
    const members = (couple.get("members") as unknown[] | undefined)?.filter((value): value is string => typeof value === "string") ?? [];

    // Shared slot references belong to the couple and must be repaired/deleted server-side.
    for (const slotId of ["slot1", "slot2"]) {
      const slotRef = db.collection("couples").doc(coupleId).collection("sharedDisplay").doc("slots")
        .collection("items").doc(slotId);
      const slot = await slotRef.get();
      if (!slot.exists) continue;
      const activeId = slot.get("mediaId") as string | undefined;
      const oldIds = (slot.get("rotationIds") as unknown[] | undefined)?.filter((value): value is string => typeof value === "string") ?? [];
      if (activeId !== mediaId && !oldIds.includes(mediaId)) continue;
      const remainingIds = [...new Set(oldIds.filter((id) => id !== mediaId))];
      if (activeId === mediaId) {
        let replacement: DocumentSnapshot | undefined;
        if (slot.get("mode") === "SLIDESHOW") {
          for (const id of remainingIds) {
            const candidate = await db.collection("couples").doc(coupleId).collection("sharedMedia").doc(id).get();
            if (candidate.exists) { replacement = candidate; break; }
          }
        }
        if (!replacement) {
          await slotRef.delete();
        } else {
          const mode = slot.get("mode") === "SLIDESHOW" && remainingIds.length > 1 ? "SLIDESHOW" : "MANUAL";
          await slotRef.update({
            mediaId: replacement.id,
            storagePath: replacement.get("storagePath"),
            thumbnailPath: replacement.get("thumbnailPath") ?? null,
            mode,
            rotationIds: mode === "SLIDESHOW" ? remainingIds : [replacement.id],
            updatedBy: actorUid,
            updatedAt: FieldValue.serverTimestamp()
          });
        }
      } else {
        const mode = slot.get("mode") === "SLIDESHOW" && remainingIds.length > 1 ? "SLIDESHOW" : "MANUAL";
        await slotRef.update({
          mode,
          rotationIds: mode === "SLIDESHOW" ? remainingIds : (activeId ? [activeId] : remainingIds),
          updatedBy: actorUid,
          updatedAt: FieldValue.serverTimestamp()
        });
      }
    }

    // A private device slot may also reference a shared photo. Clean those references even
    // when the other member's app is closed, so its next sync can evict the stale widget cache.
    for (const memberUid of members) {
      for (const slotId of ["slot1", "slot2"]) {
        const slotRef = db.collection("users").doc(memberUid).collection("displaySettings").doc("default")
          .collection("slots").doc(slotId);
        const slot = await slotRef.get();
        if (!slot.exists) continue;
        const activeId = slot.get("mediaId") as string | undefined;
        const oldIds = (slot.get("rotationIds") as unknown[] | undefined)?.filter((value): value is string => typeof value === "string") ?? [];
        if (activeId !== mediaId && !oldIds.includes(mediaId)) continue;
        const remainingIds = [...new Set(oldIds.filter((id) => id !== mediaId))];
        if (activeId === mediaId) {
          let replacement: DocumentSnapshot | undefined;
          if (slot.get("mode") === "SLIDESHOW") {
            for (const id of remainingIds) {
              replacement = await findAccessibleMediaForPersonalSlot(memberUid, coupleId, id);
              if (replacement) break;
            }
          }
          if (!replacement) {
            await slotRef.delete();
          } else {
            const mode = slot.get("mode") === "SLIDESHOW" && remainingIds.length > 1 ? "SLIDESHOW" : "MANUAL";
            await slotRef.update({
              mediaId: replacement.id,
              storagePath: replacement.get("storagePath"),
              thumbnailPath: replacement.get("thumbnailPath") ?? null,
              mode,
              rotationIds: mode === "SLIDESHOW" ? remainingIds : [replacement.id],
              updatedBy: actorUid || memberUid,
              updatedAt: FieldValue.serverTimestamp()
            });
          }
        } else {
          const mode = slot.get("mode") === "SLIDESHOW" && remainingIds.length > 1 ? "SLIDESHOW" : "MANUAL";
          await slotRef.update({
            mode,
            rotationIds: mode === "SLIDESHOW" ? remainingIds : (activeId ? [activeId] : remainingIds),
            updatedBy: actorUid || memberUid,
            updatedAt: FieldValue.serverTimestamp()
          });
        }
      }
    }
  }
);
export const notifySharedSlot = onDocumentUpdated(
  "couples/{coupleId}/sharedDisplay/slots/items/{slotId}",
  async (event) => {
    const before = event.data?.before.data();
    const after = event.data?.after.data();
    if (!after) return;
    if (before && before.mode === "SLIDESHOW" && after.mode === "SLIDESHOW" &&
        before.caption === after.caption &&
        JSON.stringify(before.rotationIds ?? []) === JSON.stringify(after.rotationIds ?? []) &&
        before.intervalMinutes === after.intervalMinutes && before.mediaId !== after.mediaId) {
      return; // Automatic slideshow tick: sync silently instead of sending repeated notifications.
    }
    await notifyOtherMembers(
      event.params.coupleId,
      typeof after.updatedBy === "string" ? after.updatedBy : "",
      "Yang Dipasang diperbarui ♥",
      `Konfigurasi ${event.params.slotId === "slot1" ? "Slot 1" : "Slot 2"} bersama berubah.`,
      "shared_slot_updated"
    );
  }
);


async function cleanupPersonalSlotsAfterDisconnect(uid: string): Promise<void> {
  const privateMedia = db.collection("users").doc(uid).collection("privateMedia");
  for (const slotId of ["slot1", "slot2"]) {
    const slotRef = db.collection("users").doc(uid).collection("displaySettings").doc("default")
      .collection("slots").doc(slotId);
    const slot = await slotRef.get();
    if (!slot.exists) continue;
    const activeId = slot.get("mediaId") as string | undefined;
    const oldIds = (slot.get("rotationIds") as unknown[] | undefined)?.filter((value): value is string => typeof value === "string") ?? [];
    const candidateIds = [...new Set([...oldIds, ...(activeId ? [activeId] : [])])];
    const snapshots = candidateIds.length > 0
      ? await db.getAll(...candidateIds.map((id) => privateMedia.doc(id)))
      : [];
    const mediaById = new Map(snapshots.map((media) => [media.id, media]));
    const privateIds = oldIds.filter((id) => mediaById.get(id)?.exists);
    const activeIsPrivate = !!activeId && !!mediaById.get(activeId)?.exists;

    if (activeIsPrivate && activeId) {
      const rotationIds = privateIds.includes(activeId) ? privateIds : [activeId, ...privateIds];
      const mode = slot.get("mode") === "SLIDESHOW" && rotationIds.length > 1 ? "SLIDESHOW" : "MANUAL";
      await slotRef.update({
        rotationIds: mode === "SLIDESHOW" ? rotationIds : [activeId],
        mode,
        updatedBy: uid,
        updatedAt: FieldValue.serverTimestamp()
      });
      continue;
    }

    const replacementId = slot.get("mode") === "SLIDESHOW"
      ? privateIds.find((id) => !!mediaById.get(id)?.exists)
      : undefined;
    const replacement = replacementId ? mediaById.get(replacementId) : undefined;
    if (!replacement?.exists || !replacementId) {
      await slotRef.delete();
      continue;
    }
    const rotationIds = privateIds.includes(replacementId) ? privateIds : [replacementId, ...privateIds];
    const mode = rotationIds.length > 1 ? "SLIDESHOW" : "MANUAL";
    await slotRef.update({
      mediaId: replacementId,
      storagePath: replacement.get("storagePath"),
      thumbnailPath: replacement.get("thumbnailPath") ?? null,
      rotationIds: mode === "SLIDESHOW" ? rotationIds : [replacementId],
      mode,
      updatedBy: uid,
      updatedAt: FieldValue.serverTimestamp()
    });
  }
}

async function deleteDocsInCollection(path: string): Promise<void> {
  // Delete in bounded batches instead of loading an arbitrarily large gallery into memory.
  while (true) {
    const snapshot = await db.collection(path).orderBy(FieldPath.documentId()).limit(400).get();
    if (snapshot.empty) break;
    const batch = db.batch();
    snapshot.docs.forEach((document) => batch.delete(document.ref));
    await batch.commit();
  }
}

/**
 * Dissolving a pair revokes access first, then attempts to remove shared media and metadata.
 * Private user media are never touched. Shared media are deleted for both members, as stated
 * in the app confirmation dialog and README.
 */
export const disconnectCouple = onCall({ maxInstances: 5 }, async (request) => {
  const uid = requireUid(request.auth?.uid);
  const userRef = db.collection("users").doc(uid);
  const user = await userRef.get();
  const coupleId = user.get("coupleId") as string | undefined;
  if (!coupleId) throw new HttpsError("failed-precondition", "Akun ini belum terhubung dengan pasangan.");
  const coupleRef = db.collection("couples").doc(coupleId);
  let members: string[] = [];

  await db.runTransaction(async (tx) => {
    const [freshUser, couple] = await Promise.all([tx.get(userRef), tx.get(coupleRef)]);
    if (!freshUser.exists || !couple.exists || couple.get("status") !== "active") {
      throw new HttpsError("failed-precondition", "Hubungan pasangan sudah tidak aktif.");
    }
    const transactionMembers = (couple.get("members") as unknown[] | undefined)?.filter((value): value is string => typeof value === "string") ?? [];
    if (!transactionMembers.includes(uid) || freshUser.get("coupleId") !== coupleId || transactionMembers.length !== 2) {
      throw new HttpsError("permission-denied", "Keanggotaan pasangan tidak valid.");
    }
    const memberDocs = await Promise.all(transactionMembers.map((memberUid) => tx.get(db.collection("users").doc(memberUid))));
    members = transactionMembers;
    // Firestore requires all reads to finish before the first write in a transaction.
    tx.update(coupleRef, {
      status: "dissolved",
      dissolvedAt: FieldValue.serverTimestamp(),
      dissolvedBy: uid
    });
    memberDocs.forEach((member, index) => {
      if (member.exists && member.get("coupleId") === coupleId) {
        tx.update(db.collection("users").doc(transactionMembers[index]), { coupleId: null, updatedAt: FieldValue.serverTimestamp() });
      }
    });
  });

  const cleanupErrors: string[] = [];
  try {
    await getStorage().bucket().deleteFiles({ prefix: `couples/${coupleId}/shared/` });
  } catch (error) {
    logger.error("Shared Storage cleanup failed after couple dissolution", { coupleId, error });
    cleanupErrors.push("storage");
  }
  for (const path of [
    `couples/${coupleId}/sharedMedia`,
    `couples/${coupleId}/albums`,
    `couples/${coupleId}/activity`,
    `couples/${coupleId}/sharedDisplay/slots/items`
  ]) {
    try { await deleteDocsInCollection(path); }
    catch (error) {
      logger.error("Shared Firestore cleanup failed after couple dissolution", { path, error });
      cleanupErrors.push(path);
    }
  }
  for (const memberUid of members) {
    try {
      await cleanupPersonalSlotsAfterDisconnect(memberUid);
    } catch (error) {
      logger.error("Personal slot cleanup failed after couple dissolution", { coupleId, memberUid, error });
      cleanupErrors.push(`personalSlots:${memberUid}`);
    }
  }
  try {
    for (const memberUid of members) {
      let cursor: QueryDocumentSnapshot | undefined;
      while (true) {
        let query = db.collection("pairingCodes").where("ownerUid", "==", memberUid)
          .orderBy(FieldPath.documentId()).limit(400);
        if (cursor) query = query.startAfter(cursor);
        const page = await query.get();
        if (page.empty) break;
        const unusedCodes = page.docs.filter((doc) => !doc.get("usedAt"));
        for (let offset = 0; offset < unusedCodes.length; offset += 400) {
          const batch = db.batch();
          unusedCodes.slice(offset, offset + 400).forEach((doc) => batch.delete(doc.ref));
          await batch.commit();
        }
        cursor = page.docs[page.docs.length - 1];
        if (page.size < 400) break;
      }
    }
  } catch (error) {
    logger.error("Pairing-code cleanup failed after couple dissolution", { coupleId, error });
    cleanupErrors.push("pairingCodes");
  }
  return { disconnected: true, cleanupPending: cleanupErrors.length > 0 };
});
