package com.plehooo.cinta

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoRoutingTest {
    private val coupleId = "c-1"
    private val a = "user-a"
    private val b = "user-b"

    private fun member(uid: String, couple: String = coupleId, leftAt: String? = null) =
        CoupleMember(coupleId = couple, userId = uid, leftAt = leftAt)

    @Test fun phoneAOnlyRoutesToPhotoB() {
        val route = PhotoRouting.forViewer(listOf(member(a), member(b)), a)!!
        assertEquals(a, route.viewerUid)
        assertEquals(b, route.partnerUid)
        assertFalse(PhotoRouting.canAffectWidget(route, a))
        assertTrue(PhotoRouting.canAffectWidget(route, b))
    }

    @Test fun phoneBOnlyRoutesToPhotoA() {
        val route = PhotoRouting.forViewer(listOf(member(a), member(b)), b)!!
        assertEquals(a, route.partnerUid)
        assertFalse(PhotoRouting.canAffectWidget(route, b))
        assertTrue(PhotoRouting.canAffectWidget(route, a))
    }

    @Test fun thirdActiveMemberFailsClosed() {
        assertNull(PhotoRouting.forViewer(listOf(member(a), member(b), member("user-c")), a))
    }

    @Test fun unpairedOrRemovedMemberHasNoRoute() {
        assertNull(PhotoRouting.forViewer(listOf(member(a)), a))
        assertNull(PhotoRouting.forViewer(listOf(member(a), member(b, leftAt = "2026-01-01T00:00:00Z")), a))
        assertNull(PhotoRouting.forViewer(listOf(member(b)), a))
    }

    @Test fun multipleActiveMembershipRowsFailClosed() {
        assertNull(PhotoRouting.forViewer(listOf(member(a), member(b), member(a, "c-2")), a))
    }
}
