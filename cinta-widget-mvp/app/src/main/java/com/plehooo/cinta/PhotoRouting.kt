package com.plehooo.cinta

/** The only permitted route for a home-screen widget: viewer -> exactly one active partner. */
data class WidgetRoute(
    val coupleId: String,
    val viewerUid: String,
    val partnerUid: String
)

object PhotoRouting {
    /**
     * A route is returned only when the viewer belongs to a couple with exactly two active
     * members. Missing membership, a single-member invite room, or any unexpected third member
     * fails closed rather than selecting an arbitrary account.
     */
    fun forViewer(members: List<CoupleMember>, viewerUid: String): WidgetRoute? {
        if (viewerUid.isBlank()) return null
        val active = members.filter { it.leftAt == null }
        val ownMemberships = active.filter { it.userId == viewerUid }
        if (ownMemberships.size != 1) return null
        val mine = ownMemberships.single()
        val coupleMembers = active.filter { it.coupleId == mine.coupleId }
        if (coupleMembers.size != 2) return null
        val partner = coupleMembers.singleOrNull { it.userId != viewerUid } ?: return null
        if (partner.userId == viewerUid) return null
        return WidgetRoute(mine.coupleId, viewerUid, partner.userId)
    }

    /** Defense-in-depth check: a widget can only use a photo owned by its partner. */
    fun canAffectWidget(route: WidgetRoute, publishedOwnerUid: String): Boolean =
        publishedOwnerUid == route.partnerUid && publishedOwnerUid != route.viewerUid
}
