package com.odasi.fieldwright.feature.spaces

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpacesViewModelTest {

    @Test
    fun createSpaceAndFollowWorksAndAddsToTop() {
        val vm = SpacesViewModel()
        val initialSize = vm.state.value.spaces.size
        vm.createSpace("Frisco Hail Command", "Frisco North", "Storm Crew", "Ridge inspections")
        assertEquals(initialSize + 1, vm.state.value.spaces.size)
        val created = vm.state.value.spaces.first()
        assertEquals("frisco-hail-command", created.slug)
        assertTrue(created.following)

        vm.toggleFollow(created.id)
        assertFalse(vm.state.value.spaces.first().following)
    }

    @Test
    fun chronologicalFeedPublishKeepReplyAndLiveToggle() {
        val vm = SpacesViewModel()
        val countBefore = vm.state.value.posts.size
        vm.publishPost(
            kind = FieldPostKind.NOTE,
            title = "Hail square check",
            body = "Found 9 bruising hits on east facet.",
            spaceId = "sp-ntx-hail",
            photoLabels = listOf("east_facet.jpg"),
            attachedJobLabel = null,
        )
        assertEquals(countBefore + 1, vm.state.value.posts.size)
        val top = vm.state.value.posts.first()
        assertEquals(listOf("east_facet.jpg"), top.photoLabels)

        vm.toggleKeep(top.id)
        assertTrue(vm.state.value.posts.first().keptByMe)
        assertEquals(1, vm.state.value.posts.first().keepCount)

        vm.addReply(top.id, "Copy, scheduling adjuster walk.")
        assertEquals(1, vm.state.value.posts.first().replies.size)

        assertFalse(vm.state.value.liveActive)
        vm.toggleLiveStream("Live Roof Walk")
        assertTrue(vm.state.value.liveActive)
        assertEquals("Live Roof Walk", vm.state.value.liveTitle)
    }
}
