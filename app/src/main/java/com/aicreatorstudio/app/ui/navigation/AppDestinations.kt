package com.aicreatorstudio.app.ui.navigation

/**
 * Central place for navigation route names.
 *
 * Only [HOME] is a "real" screen today. [NEW_PROJECT], [IMPORT_VIDEO],
 * [SETTINGS] and [PROJECT_DETAIL] are placeholder destinations that exist
 * so navigation is fully wired up ahead of time — their actual editing/AI
 * functionality is built later without touching this graph again.
 */
object AppDestinations {
    const val SPLASH = "splash"
    const val HOME = "home"
    const val NEW_PROJECT = "new_project"
    const val IMPORT_VIDEO = "import_video"
    const val SETTINGS = "settings"

    const val PROJECT_ID_ARG = "projectId"
    const val PROJECT_DETAIL = "project_detail/{$PROJECT_ID_ARG}"

    /**
     * 2026-10-20 — optional deep-link to a specific editor tool panel.
     *
     * The home screen's AI capability tiles open the editor with the
     * matching tool already open, so tapping "متن‌یابی هوشمند" lands
     * on the OCR panel instead of the timeline. The value is a
     * [com.aicreatorstudio.app.model.ToolPanel] enum name — one
     * namespace, no second id scheme to keep in sync.
     *
     * ## Why a path segment and not a query parameter
     *
     * The tool is a **path** argument, not a query one, on purpose.
     * Navigation only matches query arguments when the route PATTERN
     * itself carries a query string — `NavDeepLink.isParameterizedQuery`
     * is `Uri.parse(uriPattern).getQuery() != null`, i.e. it is derived
     * from the declared route, not from the URI being navigated to.
     * A `navArgument("tool")` declared next to a
     * `project_detail/{projectId}` route is therefore never filled:
     * the deep link matches, the argument stays null, and the tool
     * silently does not open. Carrying the tool in the path keeps the
     * behaviour obvious and uses the one argument form Navigation
     * matches unconditionally.
     */
    const val TOOL_ARG = "tool"
    const val PROJECT_DETAIL_TOOL = "project_detail/{$PROJECT_ID_ARG}/tool/{$TOOL_ARG}"

    fun projectDetailRoute(projectId: String): String = "project_detail/$projectId"

    fun projectDetailToolRoute(projectId: String, tool: String): String =
        "project_detail/$projectId/tool/$tool"
}
