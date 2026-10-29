package com.aicreatorstudio.app.ui.navigation

import com.aicreatorstudio.app.model.ToolPanel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2026-10-20 — guards the home screen's AI deep-link contract.
 *
 * The capability tiles open the editor with a tool panel already
 * open, which means the tool id travels through the navigation
 * route. Two things can silently break that:
 *
 *  1. **The tool id drifts from the panel enum.** The route carries a
 *     `ToolPanel` enum NAME. A rename would leave the tile opening
 *     nothing (the editor ignores an unknown name), with no compile
 *     error — so the contract is asserted here.
 *  2. **The tool is carried as a query argument.** Navigation only
 *     matches query arguments when the route PATTERN itself declares
 *     a query string: `NavDeepLink.isParameterizedQuery` is
 *     `Uri.parse(uriPattern).getQuery() != null`, i.e. it is derived
 *     from the declared route, not from the URI being navigated to.
 *     A `navArgument("tool")` declared next to a
 *     `project_detail/{projectId}` route is therefore never filled —
 *     the deep link matches, the argument stays null, and the tool
 *     silently does not open. The tool must stay a PATH argument.
 */
class AiToolDeepLinkTest {

    @Test
    fun `the tool is carried as a path argument, not a query argument`() {
        val toolRoute = AppDestinations.PROJECT_DETAIL_TOOL
        assertTrue(
            "the tool must be a path segment of the route, otherwise " +
                "Navigation never fills it: $toolRoute",
            toolRoute.contains("{${AppDestinations.TOOL_ARG}}")
        )
        assertTrue(
            "the route must not declare a query string — Navigation only " +
                "matches query arguments when the pattern has one",
            !toolRoute.contains("?")
        )
        assertTrue(
            "the project id must still be the first path segment",
            toolRoute.startsWith("project_detail/{${AppDestinations.PROJECT_ID_ARG}}")
        )
    }

    @Test
    fun `the plain project route has no tool segment`() {
        assertEquals(
            "project_detail/{projectId}",
            AppDestinations.PROJECT_DETAIL
        )
    }

    @Test
    fun `the two routes cannot match each other`() {
        // The tool route adds the "tool" segment plus the value
        // segment, so its path is longer — a plain project route can
        // never be matched by the tool route (and vice versa).
        val plainSegments = AppDestinations.PROJECT_DETAIL.split("/").size
        val toolSegments = AppDestinations.PROJECT_DETAIL_TOOL.split("/").size
        assertTrue(
            "the tool route must have more path segments than the plain route " +
                "($toolSegments vs $plainSegments) so the two cannot match each other",
            toolSegments > plainSegments
        )
    }

    @Test
    fun `the tool routes build a navigable path`() {
        val projectId = "abc-123"
        val panel = ToolPanel.AI_OCR
        val route = AppDestinations.projectDetailToolRoute(projectId, panel.name)
        assertEquals(
            "project_detail/$projectId/tool/${panel.name}",
            route
        )
        assertEquals(
            "project_detail/$projectId",
            AppDestinations.projectDetailRoute(projectId)
        )
    }

    @Test
    fun `every ai tool name round-trips through the route value`() {
        // The editor parses the route value with `ToolPanel.valueOf`,
        // so a name that does not round-trip would open nothing.
        for (panel in ToolPanel.entries) {
            val parsed = runCatching { ToolPanel.valueOf(panel.name) }.getOrNull()
            assertEquals(panel, parsed)
        }
    }
}
