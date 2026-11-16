from calamus_api import Plugin as BasePlugin


class Plugin(BasePlugin):
    def on_load(self, context):
        self.context = context
        self.accounts = {}
        self.capture = bool(context.setting("capture"))
        self.ghost_default = bool(context.setting("ghost"))
        context.register_command("archive", "Локальный архив этого чата")
        context.register_command("deleted", "Удалённые сообщения этого чата")
        context.register_command("history", "История изменений: .history ID")
        context.register_message_action("history", "История изменений")
        if context.has_permission("presence.control"):
            context.register_command("ghost", "Режим призрака: .ghost on/off/status")
        if context.has_permission("presence.read"):
            context.register_command("lastseen", "Доступное время захода собеседника")
        self.refresh_accounts()

    def refresh_accounts(self):
        accounts = {}
        for value in self.context.accounts():
            account = self.context.account(value["account_id"])
            accounts[(account.account_id, account.user_id)] = account
            if self.context.has_permission("presence.control"):
                active = account.get("archive_ghost", self.ghost_default)
                if active:
                    account.set_ghost(True)
        self.accounts = accounts

    def notice(self, text):
        try:
            if self.context.has_permission("notifications"):
                self.context.notify(text)
            else:
                self.context.log(text)
        except RuntimeError:
            self.log(text)

    def log(self, text):
        try:
            self.context.log(text)
        except RuntimeError:
            pass

    def on_event(self, name, payload):
        try:
            self.handle_event(name, payload)
        except RuntimeError as error:
            self.report_error(error)

    def handle_event(self, name, payload):
        if name == "account.changed":
            self.refresh_accounts()
            return
        if name == "app.foreground":
            self.refresh_accounts()
            return
        if name == "settings.changed":
            if payload.get("key") == "capture":
                self.capture = bool(payload["value"])
            if payload.get("key") == "ghost" and self.context.has_permission("presence.control"):
                self.ghost_default = bool(payload["value"])
                for account in self.accounts.values():
                    account.set("archive_ghost", bool(payload["value"]))
                    account.set_ghost(bool(payload["value"]))
            return
        account = self.accounts.get((payload.get("account_id"), payload.get("user_id")))
        if account is None:
            return
        if name == "message.action" and payload.get("action") == "history":
            message = payload["message"]
            try:
                if self.capture and message.get("archivable"):
                    account.archive_record(message)
                account.show_archive(payload["dialog_id"], message_id=message["id"])
            except RuntimeError as error:
                self.report_error(error)
            return
        if name in ("message.received", "message.updated", "message.sent") and self.capture:
            messages = payload.get("messages", [])
            if payload.get("message"):
                messages = [payload["message"]]
            for message in messages:
                if message.get("id", 0) > 0 and message.get("archivable"):
                    try:
                        account.archive_record(message)
                    except RuntimeError as error:
                        self.log(f"Не удалось сохранить сообщение {message.get('id')}: {error}")

    def report_error(self, error):
        text = str(error)
        self.notice("Подождите несколько секунд и повторите действие" if "rate limit" in text.lower() else text)

    def on_command(self, name, arguments, chat):
        try:
            self.run_command(name, arguments, chat)
        except RuntimeError as error:
            self.report_error(error)

    def run_command(self, name, arguments, chat):
        account = self.accounts.get((chat.get("account_id"), chat.get("user_id")))
        if account is None:
            self.notice("Сначала разрешите плагину доступ к этому аккаунту и чату")
            return
        dialog = chat["dialog_id"]
        if name == "archive":
            account.show_archive(dialog)
        elif name == "deleted":
            account.show_archive(dialog, deleted_only=True)
        elif name == "history":
            try:
                message = int(arguments.strip())
                if message <= 0:
                    raise ValueError()
            except ValueError:
                self.notice("Откройте меню сообщения → История изменений или введите .history ID")
                return
            account.show_archive(dialog, message_id=message)
        elif name == "ghost":
            mode = arguments.strip().lower()
            current = bool(account.get("archive_ghost", self.ghost_default))
            if mode == "status":
                self.notice("Режим призрака включён" if current else "Режим призрака выключен")
                return
            if mode not in ("", "on", "off", "вкл", "выкл"):
                self.notice("Используйте .ghost on, .ghost off или .ghost status")
                return
            active = not current if not mode else mode in ("on", "вкл")
            account.set_ghost(active)
            account.set("archive_ghost", active)
            self.notice("Режим призрака включён" if active else "Режим призрака выключен")
        elif name == "lastseen":
            if dialog <= 0:
                self.notice("Команда доступна в личном чате с пользователем")
                return
            status = account.presence(dialog)
            kind = status.get("kind")
            if kind == "online":
                text = "Telegram сообщает: пользователь в сети"
            elif status.get("exact") and status.get("timestamp", 0) > 0:
                text = "Последний известный заход: " + status.get("formatted", str(status["timestamp"]))
            else:
                titles = {"recently": "был(а) недавно", "last_week": "был(а) на этой неделе", "last_month": "был(а) в этом месяце", "unknown": "время захода недоступно"}
                text = titles.get(kind, "Точное время недоступно")
                if status.get("by_me"):
                    text += ". Telegram скрывает точное время из-за ваших настроек видимости. Для исключения из взаимного ограничения нужен Premium."
                else:
                    text += ". Сервер не передал точную метку времени."
            account.show_dialog("Статус собеседника", text)
