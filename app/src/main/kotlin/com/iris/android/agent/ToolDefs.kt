package com.iris.android.agent

object ToolDefs {
    private fun p(type: String, desc: String, items: ToolParam? = null) = ToolParam(type, desc, items)

    val ALL: List<ToolDef> = listOf(
        ToolDef(
            "open_app",
            "Open an installed app by its name, e.g. 'WhatsApp' or 'Chrome'.",
            mapOf("appName" to p("string", "The app's display name")),
            required = listOf("appName")
        ),
        ToolDef(
            "list_installed_apps",
            "List apps installed on this phone.",
            emptyMap()
        ),
        ToolDef(
            "toggle_flashlight",
            "Turn the flashlight/torch on or off.",
            mapOf("on" to p("boolean", "true to turn on, false to turn off")),
            required = listOf("on")
        ),
        ToolDef(
            "set_volume",
            "Set the media volume as a percentage (0-100).",
            mapOf("percent" to p("number", "Volume percentage from 0 to 100")),
            required = listOf("percent")
        ),
        ToolDef(
            "toggle_do_not_disturb",
            "Turn Do Not Disturb mode on or off (requires Notification Policy Access, granted once in Settings).",
            mapOf("on" to p("boolean", "true to enable DND, false to disable")),
            required = listOf("on")
        ),
        ToolDef(
            "open_quick_toggle_panel",
            "Open the system panel for a toggle Android doesn't allow apps to flip directly (WiFi, Bluetooth, Airplane mode) — the user still taps it themselves.",
            mapOf("setting" to p("string", "One of: wifi, bluetooth, airplane_mode")),
            required = listOf("setting")
        ),
        ToolDef(
            "take_screenshot",
            "Capture a screenshot of the current screen and save it locally.",
            emptyMap()
        ),
        ToolDef(
            "read_file",
            "Read the text contents of a file the user has previously granted access to (via the file picker).",
            mapOf("uri" to p("string", "The content:// URI of a previously picked file")),
            required = listOf("uri")
        ),
        ToolDef(
            "write_note_file",
            "Save a text file into IRIS's own app storage (Documents/IRIS folder).",
            mapOf(
                "filename" to p("string", "File name, e.g. notes.txt"),
                "content" to p("string", "Text content to write")
            ),
            required = listOf("filename", "content"),
            dangerous = true
        ),
        ToolDef(
            "remember",
            "Save a fact or preference to long-term local memory for later recall.",
            mapOf(
                "text" to p("string", "The fact to remember"),
                "tags" to p("array", "Optional short tags", items = p("string", "tag"))
            ),
            required = listOf("text")
        ),
        ToolDef(
            "recall",
            "Search local long-term memory for relevant past context.",
            mapOf("query" to p("string", "What to search memory for")),
            required = listOf("query")
        ),
        ToolDef(
            "create_reminder",
            "Schedule a local notification reminder.",
            mapOf(
                "title" to p("string", "Reminder title"),
                "body" to p("string", "Reminder details"),
                "minutesFromNow" to p("number", "Minutes from now to fire the reminder")
            ),
            required = listOf("title", "minutesFromNow")
        ),
        ToolDef(
            "get_recent_notifications",
            "Get the most recent notifications IRIS has captured (title, app, text).",
            mapOf("limit" to p("number", "Max number of notifications to return, default 10"))
        ),
        ToolDef(
            "reply_to_notification",
            "Send a reply to a message notification (e.g. WhatsApp, SMS) using its built-in quick-reply action, by notification key.",
            mapOf(
                "notificationKey" to p("string", "The key of the notification to reply to, from get_recent_notifications"),
                "message" to p("string", "The reply text to send")
            ),
            required = listOf("notificationKey", "message"),
            dangerous = true
        ),
        ToolDef(
            "send_whatsapp_message",
            "Compose and send a WhatsApp message to a contact by name or phone number. Uses on-screen automation since WhatsApp has no public send API — requires the Accessibility automation permission to be enabled.",
            mapOf(
                "contact" to p("string", "Contact name or phone number"),
                "message" to p("string", "Message text to send")
            ),
            required = listOf("contact", "message"),
            dangerous = true
        ),
        ToolDef(
            "call_contact",
            "Place a phone call to a contact by name or phone number.",
            mapOf("contact" to p("string", "Contact name or phone number")),
            required = listOf("contact"),
            dangerous = true
        ),
        ToolDef(
            "get_device_status",
            "Get battery level, network connection type, and ringer mode.",
            emptyMap()
        )
    )
}
