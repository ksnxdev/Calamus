from calamus_api import Plugin as BasePlugin


class Plugin(BasePlugin):
    def on_load(self, context):
        self.context = context
        launches = context.get("launches", 0) + 1
        context.set("launches", launches)
        context.log(f"Loaded {launches} time(s)")
        context.register_command("hello", "Local greeting")
        if context.has_permission("accounts.read") and context.has_permission("messages.send"):
            context.register_command("ping", "Send a greeting")
        if context.has_permission("accounts.read") and context.has_permission("chats.read") and context.has_permission("ui"):
            context.register_command("chatinfo", "Show chat information")
        if context.has_permission("accounts.read") and context.has_permission("messages.read") and context.has_permission("ui"):
            context.register_command("recent", "Show recent messages")
        if context.has_permission("notifications"):
            context.notify("Hello from Python!")

    def on_event(self, name, payload):
        self.context.log(name)
        self.context.set("last_event", name)

    def on_command(self, name, arguments, chat):
        if name == "hello":
            greeting = self.context.setting("greeting")
            self.context.log(greeting)
            if self.context.has_permission("notifications"):
                self.context.notify(greeting)
            return
        account = self.context.account(chat["account_id"])
        dialog_id = chat["dialog_id"]
        if name == "ping":
            text = arguments or self.context.setting("greeting")
            if self.context.setting("include_name"):
                user = next(a for a in self.context.accounts() if a["account_id"] == account.account_id)
                text = user["name"] + ": " + text
            account.send_message(dialog_id, text)
            account.set("greetings_sent", account.get("greetings_sent", 0) + 1)
        elif name == "chatinfo":
            info = next((item for item in account.chats(100) if item["dialog_id"] == dialog_id), {})
            account.show_dialog("Chat information", info.get("title", str(dialog_id)))
        elif name == "recent":
            messages = account.history(dialog_id, 5)
            account.show_dialog("Recent messages", "\n".join(m["text"][:300] for m in messages) or "No messages")

    def on_unload(self):
        self.context.log("Unloaded")
