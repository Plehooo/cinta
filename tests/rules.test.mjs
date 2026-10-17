import { after, before, test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} from '@firebase/rules-unit-testing';
import { doc, getDoc, setDoc } from 'firebase/firestore';
import { getBytes, ref, uploadBytes } from 'firebase/storage';

let env;

before(async () => {
  env = await initializeTestEnvironment({
    projectId: 'demo-galeri-cinta',
    firestore: { rules: readFileSync('firestore.rules', 'utf8') },
    storage: { rules: readFileSync('storage.rules', 'utf8') },
  });
  await env.withSecurityRulesDisabled(async (context) => {
    await setDoc(doc(context.firestore(), 'couples/couple-test'), {
      members: ['alice', 'bob'], status: 'active', schemaVersion: 1,
    });
    await setDoc(doc(context.firestore(), 'users/alice'), { coupleId: 'couple-test' });
    await setDoc(doc(context.firestore(), 'users/bob'), { coupleId: 'couple-test' });
  });
});

after(async () => {
  await env?.cleanup();
});

test('private media metadata is readable only by its owner', async () => {
  const alice = env.authenticatedContext('alice').firestore();
  const bob = env.authenticatedContext('bob').firestore();
  await assertSucceeds(setDoc(doc(alice, 'users/alice/privateMedia/private1'), {
    ownerUid: 'alice', visibility: 'private',
    storagePath: 'users/alice/private/private1/original', thumbnailPath: null,
    caption: 'private memory', mimeType: 'image/jpeg', createdAt: new Date(), updatedAt: new Date(),
  }));
  await assertSucceeds(getDoc(doc(alice, 'users/alice/privateMedia/private1')));
  await assertFails(getDoc(doc(bob, 'users/alice/privateMedia/private1')));
});

test('shared media is restricted to active couple members', async () => {
  const alice = env.authenticatedContext('alice').firestore();
  const bob = env.authenticatedContext('bob').firestore();
  const outsider = env.authenticatedContext('eve').firestore();
  const sharedDoc = doc(alice, 'couples/couple-test/sharedMedia/shared1');
  await assertSucceeds(setDoc(sharedDoc, {
    ownerUid: 'alice', visibility: 'shared',
    storagePath: 'couples/couple-test/shared/alice/shared1/original', thumbnailPath: null,
    caption: 'shared memory', mimeType: 'image/jpeg', createdAt: new Date(), updatedAt: new Date(),
  }));
  await assertSucceeds(getDoc(doc(bob, 'couples/couple-test/sharedMedia/shared1')));
  await assertFails(getDoc(doc(outsider, 'couples/couple-test/sharedMedia/shared1')));
});

test('only a couple member can update a valid shared slot', async () => {
  const alice = env.authenticatedContext('alice').firestore();
  const outsider = env.authenticatedContext('eve').firestore();
  // Isolate this test from execution order: seed the referenced media as trusted fixture data.
  await env.withSecurityRulesDisabled(async (context) => {
    await setDoc(doc(context.firestore(), 'couples/couple-test/sharedMedia/slot-media'), {
      ownerUid: 'alice', visibility: 'shared',
      storagePath: 'couples/couple-test/shared/alice/slot-media/original', thumbnailPath: null,
      caption: 'slot fixture', mimeType: 'image/jpeg', createdAt: new Date(), updatedAt: new Date(),
    });
  });
  const slot = doc(alice, 'couples/couple-test/sharedDisplay/slots/items/slot1');
  const data = {
    mediaId: 'slot-media', storagePath: 'couples/couple-test/shared/alice/slot-media/original',
    thumbnailPath: null, caption: 'Kenangan', mode: 'MANUAL', rotationIds: ['slot-media'],
    intervalMinutes: 60, source: 'shared', updatedBy: 'alice', updatedAt: new Date(),
  };
  await assertSucceeds(setDoc(slot, data));
  await assertFails(setDoc(doc(alice, 'couples/couple-test/sharedDisplay/slots/items/slot2'), {
    ...data, mediaId: 'slot-media', storagePath: 'couples/another-couple/shared/alice/slot-media/original',
  }));
  await assertFails(setDoc(doc(outsider, 'couples/couple-test/sharedDisplay/slots/items/slot2'), {
    ...data, updatedBy: 'eve',
  }));
});

test('private Storage objects cannot be read by another account', async () => {
  const aliceStorage = env.authenticatedContext('alice').storage();
  const bobStorage = env.authenticatedContext('bob').storage();
  const object = ref(aliceStorage, 'users/alice/private/private1/original');
  await assertSucceeds(uploadBytes(object, new Uint8Array([1, 2, 3]), { contentType: 'image/jpeg' }));
  await assertSucceeds(getBytes(object));
  await assertFails(getBytes(ref(bobStorage, 'users/alice/private/private1/original')));
});

test('private slot accepts only a real media reference owned by the same account', async () => {
  const alice = env.authenticatedContext('alice').firestore();
  const outsider = env.authenticatedContext('eve').firestore();
  await env.withSecurityRulesDisabled(async (context) => {
    await setDoc(doc(context.firestore(), 'users/alice/privateMedia/slot-private1'), {
      ownerUid: 'alice', visibility: 'private',
      storagePath: 'users/alice/private/slot-private1/original', thumbnailPath: null,
      caption: 'private slot fixture', mimeType: 'image/jpeg', createdAt: new Date(), updatedAt: new Date(),
    });
  });
  const validSlot = {
    mediaId: 'slot-private1', storagePath: 'users/alice/private/slot-private1/original',
    thumbnailPath: null, caption: '', mode: 'MANUAL', rotationIds: ['slot-private1'],
    intervalMinutes: 60, source: 'private', updatedBy: 'alice', updatedAt: new Date(),
  };
  await assertSucceeds(setDoc(doc(alice, 'users/alice/displaySettings/default/slots/slot1'), validSlot));
  await assertFails(setDoc(doc(alice, 'users/alice/displaySettings/default/slots/slot2'), {
    ...validSlot,
    mediaId: 'not-real', storagePath: 'users/alice/private/not-real/original', rotationIds: ['not-real'],
  }));
  await assertFails(setDoc(doc(outsider, 'users/alice/displaySettings/default/slots/slot2'), validSlot));
});

test('pairing code documents cannot be read or written by clients', async () => {
  const alice = env.authenticatedContext('alice').firestore();
  await assertFails(getDoc(doc(alice, 'pairingCodes/attempted-code-hash')));
  await assertFails(setDoc(doc(alice, 'pairingCodes/attempted-code-hash'), {
    ownerUid: 'alice', expiresAt: new Date(), usedAt: null, usedBy: null,
  }));
});

test('shared Storage allows active members but denies outsiders and cross-user uploads', async () => {
  const aliceStorage = env.authenticatedContext('alice').storage();
  const bobStorage = env.authenticatedContext('bob').storage();
  const outsiderStorage = env.authenticatedContext('eve').storage();
  const ownerObject = ref(aliceStorage, 'couples/couple-test/shared/alice/shared-storage-test/original');
  await assertSucceeds(uploadBytes(ownerObject, new Uint8Array([1, 2, 3]), { contentType: 'image/jpeg' }));
  await assertSucceeds(getBytes(ref(bobStorage, 'couples/couple-test/shared/alice/shared-storage-test/original')));
  await assertFails(getBytes(ref(outsiderStorage, 'couples/couple-test/shared/alice/shared-storage-test/original')));
  await assertFails(uploadBytes(
    ref(bobStorage, 'couples/couple-test/shared/alice/forged-owner-upload/original'),
    new Uint8Array([1, 2, 3]),
    { contentType: 'image/jpeg' },
  ));
});
