package ru.milk.maxbot.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.milk.maxbot.config.AppConfig;
import ru.milk.maxbot.domain.BotUser;
import ru.milk.maxbot.domain.ConversationSession;
import ru.milk.maxbot.domain.Farm;
import ru.milk.maxbot.domain.MilkReceipt;
import ru.milk.maxbot.domain.PendingRegistration;
import ru.milk.maxbot.domain.ReceivingPoint;
import ru.milk.maxbot.domain.UserRole;
import ru.milk.maxbot.max.MaxApiClient;
import ru.milk.maxbot.max.OutgoingMessage;
import ru.milk.maxbot.repository.BotRepository;
import ru.milk.maxbot.util.Attachments;
import ru.milk.maxbot.util.Dates;
import ru.milk.maxbot.util.Jsons;
import ru.milk.maxbot.util.Keyboards;
import ru.milk.maxbot.util.Numbers;
import ru.milk.maxbot.util.Texts;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class BotService {
    private static final Logger log = LoggerFactory.getLogger(BotService.class);
    private static final Pattern PHONE_PATTERN = Pattern.compile("TEL[^:]*:([+\\d]+)");
    private static final int USERS_PAGE_SIZE = 10;
    private static final int RECEIPTS_PAGE_SIZE = 8;
    private static final int RECORDS_PAGE_SIZE = 8;

    private final AppConfig config;
    private final BotRepository repository;
    private final MaxApiClient maxApiClient;
    private final PhotoService photoService;
    private final ReportService reportService;
    private final ZoneId zoneId;

    public BotService(AppConfig config,
                      BotRepository repository,
                      MaxApiClient maxApiClient,
                      PhotoService photoService,
                      ReportService reportService) {
        this.config = config;
        this.repository = repository;
        this.maxApiClient = maxApiClient;
        this.photoService = photoService;
        this.reportService = reportService;
        this.zoneId = config.zoneId();
    }

    public void processUpdate(JsonNode update) {
        String updateType = update.path("update_type").asText();
        if (updateType.isBlank()) {
            return;
        }

        long maxUserId = extractMaxUserId(update);
        if (maxUserId == 0L) {
            log.debug("Skipping update without user id: {}", update);
            return;
        }
        if (isFromBot(update)) {
            return;
        }

        BotUser user = repository.upsertUser(
                maxUserId,
                extractChatId(update),
                extractUsername(update),
                extractFirstName(update),
                extractLastName(update),
                config.bootstrapAdminUserIds().contains(maxUserId)
        );

        switch (updateType) {
            case "bot_started", "bot_added" -> sendEntryPoint(user);
            case "message_callback" -> handleCallback(user, update);
            case "message_created" -> handleMessage(user, update);
            default -> log.debug("Ignoring update type {}", updateType);
        }
    }

    public void sendDailyDigestToUser(BotUser user, LocalDate date) {
        String text = reportService.buildDailyDigest(date);
        sendToUser(user.maxUserId(), text, listOf(
                Keyboards.callback("📊 Открыть отчёты", "report:global:start"),
                Keyboards.callback("🏠 Главное меню", "nav:home")
        ));
    }

    private void handleCallback(BotUser user, JsonNode update) {
        String callbackId = extractCallbackId(update);
        String payload = extractCallbackPayload(update);
        if (payload == null || payload.isBlank()) {
            log.warn("Callback payload was empty. Update: {}", update);
            if (callbackId != null && !callbackId.isBlank()) {
                safeAnswerCallback(callbackId, "Не удалось распознать команду");
            }
            sendToUser(user.maxUserId(), "Не удалось распознать действие. Давайте вернёмся в меню.", homeButtons(user));
            return;
        }

        try {
            if (callbackId != null && !callbackId.isBlank()) {
                safeAnswerCallback(callbackId, "Принято");
            }
            dispatchCallback(user, payload);
        } catch (Exception e) {
            log.error("Callback handling failed for payload {}", payload, e);
            sendToUser(user.maxUserId(), """
                    ⚠️ Что-то пошло не так при обработке команды.

                    Я уже вернул вас в безопасное меню, чтобы работа не остановилась.
                    """, homeButtons(user));
        }
    }

    private void dispatchCallback(BotUser user, String payload) {
        switch (payload) {
            case "start", "nav:home" -> sendEntryPoint(user);
            case "nav:cancel" -> {
                clearSession(user);
                sendEntryPoint(user);
            }
            case "help" -> sendHelp(user);
            case "status:refresh" -> sendEntryPoint(user);
            case "reg:apply" -> startRegistration(user);
            case "admin:receipt:new" -> startAdminReceiptFlow(user);
            case "receipt:new" -> startReceiptFlow(user);
            case "receipt:skip-photo" -> {
                ConversationSession session = repository.getSession(user.maxUserId());
                ObjectNode data = editableData(session);
                data.remove("draft_edit_field");
                data.put("photo_status", "MISSING");
                repository.saveSession(user.maxUserId(), "RECEIPT_CONFIRM", data);
                sendReceiptConfirmation(user, data);
            }
            case "receipt:confirm" -> saveReceipt(user);
            case "receipt:confirm:continue" -> saveReceiptAndContinueSameFarm(user);
            case "view:my_receipts" -> showMyReceipts(user);
            case "directory:farms" -> showFarmsDirectory(user);
            case "admin:requests" -> showPendingRequests(user);
            case "admin:users" -> showUsersAdmin(user);
            case "admin:farms" -> showFarmAdmin(user);
            case "admin:farm:add" -> askNewFarmName(user);
            case "admin:records" -> showAdminRecordDateMenu(user);
            case "report:farmday:start" -> startFarmDayReport(user);
            case "report:point:start" -> startPointReport(user);
            case "report:global:start" -> startGlobalReport(user);
            case "report:excel:start" -> startExcelReport(user);
            case "digest:toggle" -> toggleDigest(user);
            default -> handlePatternCallback(user, payload);
        }
    }

    private void handlePatternCallback(BotUser user, String payload) {
        if (payload.startsWith("receipt:farm:")) {
            onReceiptFarmChosen(user, payload.substring("receipt:farm:".length()));
            return;
        }
        if (payload.startsWith("receipt:point:id:")) {
            onReceiptPointChosen(user, payload.substring("receipt:point:id:".length()));
            return;
        }
        if (payload.startsWith("receipt:edit:")) {
            onReceiptDraftEdit(user, payload.substring("receipt:edit:".length()));
            return;
        }
        if (payload.startsWith("view:receipt:")) {
            showReceipt(user, parseLong(payload.substring("view:receipt:".length())));
            return;
        }
        if (payload.startsWith("edit:receipt:")) {
            beginReceiptEdit(user, payload);
            return;
        }
        if (payload.startsWith("admin:req:view:")) {
            showPendingRequestDetails(user, parseLong(payload.substring("admin:req:view:".length())));
            return;
        }
        if (payload.startsWith("admin:req:approve:")) {
            approveRegistration(user, payload.substring("admin:req:approve:".length()));
            return;
        }
        if (payload.startsWith("admin:req:point:")) {
            approveRegistrationWithPoint(user, payload.substring("admin:req:point:".length()));
            return;
        }
        if (payload.startsWith("admin:req:reject:")) {
            rejectRegistration(user, parseLong(payload.substring("admin:req:reject:".length())));
            return;
        }
        if (payload.startsWith("admin:user:view:")) {
            showUserAdminCard(user, parseLong(payload.substring("admin:user:view:".length())));
            return;
        }
        if (payload.startsWith("admin:users:page:")) {
            showUsersAdmin(user, parseIntSafe(payload.substring("admin:users:page:".length()), 0));
            return;
        }
        if (payload.startsWith("admin:user:role:")) {
            changeUserRole(user, payload.substring("admin:user:role:".length()));
            return;
        }
        if (payload.startsWith("admin:user:edit:")) {
            openUserEditMenu(user, parseLong(payload.substring("admin:user:edit:".length())));
            return;
        }
        if (payload.startsWith("admin:user:editfield:")) {
            beginUserFieldEdit(user, payload.substring("admin:user:editfield:".length()));
            return;
        }
        if (payload.startsWith("admin:user:point:")) {
            changeUserPoint(user, payload.substring("admin:user:point:".length()));
            return;
        }
        if (payload.startsWith("admin:user:toggle:")) {
            toggleUserActive(user, parseLong(payload.substring("admin:user:toggle:".length())));
            return;
        }
        if (payload.startsWith("admin:farm:toggle:")) {
            toggleFarmActive(user, parseLong(payload.substring("admin:farm:toggle:".length())));
            return;
        }
        if (payload.startsWith("admin:records:date:")) {
            showAdminRecordsForDate(user, payload.substring("admin:records:date:".length()));
            return;
        }
        if (payload.startsWith("view:my_receipts:page:")) {
            showMyReceipts(user, parseIntSafe(payload.substring("view:my_receipts:page:".length()), 0));
            return;
        }
        if (payload.startsWith("admin:record:view:")) {
            showReceipt(user, parseLong(payload.substring("admin:record:view:".length())));
            return;
        }
        if (payload.startsWith("admin:record:unlock:")) {
            unlockReceipt(user, parseLong(payload.substring("admin:record:unlock:".length())));
            return;
        }
        if (payload.startsWith("admin:record:delete:")) {
            deleteReceipt(user, parseLong(payload.substring("admin:record:delete:".length())));
            return;
        }
        if (payload.startsWith("report:farmday:farm:")) {
            chooseFarmDayReportFarm(user, parseLong(payload.substring("report:farmday:farm:".length())));
            return;
        }
        if (payload.startsWith("report:point:id:")) {
            choosePointForReport(user, parseLong(payload.substring("report:point:id:".length())));
            return;
        }
        if (payload.startsWith("report:excel:farm:")) {
            chooseFarmForExcel(user, parseLong(payload.substring("report:excel:farm:".length())));
            return;
        }
        if (payload.startsWith("report:day:")) {
            onSingleDayChoice(user, payload.substring("report:day:".length()));
            return;
        }
        if (payload.startsWith("report:period:")) {
            onPeriodChoice(user, payload.substring("report:period:".length()));
            return;
        }

        sendToUser(user.maxUserId(), "Команда не распознана. Открываю главное меню, чтобы мы не застряли.", homeButtons(user));
    }

    private void handleMessage(BotUser user, JsonNode update) {
        ConversationSession session = repository.getSession(user.maxUserId());
        JsonNode message = update.path("message");
        String text = message.path("body").path("text").asText("").trim();
        JsonNode attachments = message.path("body").path("attachments");

        try {
            String buttonAction = resolveButtonAction(user, text);
            if (buttonAction != null) {
                dispatchCallback(user, buttonAction);
                return;
            }
            if (handleGlobalCommand(user, text)) {
                return;
            }
            switch (session.state()) {
                case "REG_CONTACT" -> onRegistrationContact(user, attachments);
                case "RECEIPT_SECTION" -> onLegacyReceiptSectionInput(user, text);
                case "RECEIPT_WEIGHT" -> onReceiptWeight(user, text);
                case "RECEIPT_FAT" -> onReceiptFat(user, text);
                case "RECEIPT_PROTEIN" -> onReceiptProtein(user, text);
                case "RECEIPT_PHOTO" -> onReceiptPhoto(user, attachments, message.path("body"));
                case "REPORT_CUSTOM_START" -> onCustomStartDate(user, text);
                case "REPORT_CUSTOM_END" -> onCustomEndDate(user, text);
                case "ADMIN_ADD_FARM" -> onNewFarmName(user, text);
                case "ADMIN_RECORD_DATE" -> onAdminRecordDate(user, text);
                case "ADMIN_USER_EDIT_INPUT" -> onAdminUserEditInput(user, text);
                case "EDIT_FIELD_INPUT" -> onReceiptFieldEditInput(user, text);
                case "EDIT_PHOTO_INPUT" -> onReceiptPhotoEditInput(user, attachments);
                default -> onIdleMessage(user, text);
            }
        } catch (Exception e) {
            log.error("Message handling failed for user {}", user.maxUserId(), e);
            sendToUser(user.maxUserId(), """
                    ⚠️ Не получилось обработать сообщение.

                    Я сохранил стабильное состояние и вернул вас в меню, чтобы можно было продолжить работу без тупика.
                    """, homeButtons(user));
            clearSession(user);
        }
    }

    private void onIdleMessage(BotUser user, String text) {
        if (text.equalsIgnoreCase("/start") || text.equalsIgnoreCase("start") || text.equalsIgnoreCase("начать")) {
            sendEntryPoint(user);
            return;
        }
        sendToUser(user.maxUserId(), """
                Я всегда отвечаю и стараюсь вести вас по шагам.

                Для числовых данных я сам попрошу нужное поле, а сейчас лучше открыть меню и выбрать действие кнопкой.
                """, homeButtons(user));
    }

    private void sendEntryPoint(BotUser user) {
        clearSession(user);
        if (!user.active()) {
            sendToUser(user.maxUserId(), """
                    🔒 Доступ к боту сейчас отключён администратором.

                    Если это произошло по ошибке, пожалуйста, свяжитесь с администратором и попросите повторно активировать профиль.
                    """, listOf(Keyboards.callback("🏠 Обновить статус", "status:refresh")));
            return;
        }

        switch (user.role()) {
            case PENDING -> {
                if (repository.listPendingRegistrations().stream().anyMatch(it -> it.userId() == user.id())) {
                    sendToUser(user.maxUserId(), Texts.pendingApproval(user), pendingButtons());
                } else {
                    sendToUser(user.maxUserId(), Texts.welcomeUnknown(), unknownButtons());
                }
            }
            case EMPLOYEE -> {
                String pointName = repository.findPoint(user.receivingPointId()).map(ReceivingPoint::name).orElse("не назначен");
                sendToUser(user.maxUserId(), Texts.employeeHome(user, pointName), employeeButtons());
            }
            case DIRECTOR -> sendToUser(user.maxUserId(), Texts.managerHome(user, "директор"), directorButtons());
            case GENERAL_DIRECTOR -> sendToUser(user.maxUserId(), Texts.managerHome(user, "генеральный директор"), directorButtons());
            case ADMINISTRATOR -> sendToUser(user.maxUserId(), Texts.adminHome(user), adminButtons());
        }
    }

    private void sendHelp(BotUser user) {
        sendToUser(user.maxUserId(), """
                🙋 *Как работать с ботом*

                • Все кнопки под сообщениями встроенные. Короткие кнопки могут идти по две в ряд, длинные — по одной, чтобы на телефоне всё читалось аккуратно.
                • Приёмка проходит пошагово: колхоз → вес → жир → белок → фото → подтверждение.
                • Если один и тот же поставщик приехал в двух секциях, просто оформите две отдельные записи.
                • Если вы сотрудник, свою запись можно исправить в течение 1 часа.
                • Администратор может открыть запись повторно, поправить её или убрать из базы.
                • Директора и гендиректор видят отчёты, детали по колхозам и Excel-графики.
                • Для тестирования ролей доверенный пользователь из `BOOTSTRAP_ADMIN_USER_IDS` может использовать команды `/admin`, `/director`, `/gendirector`, `/employee`, `/user`, `/pending`, `/chepas`, `/arat`, `/pilna`, `/role`.
                """, homeButtons(user));
    }

    private void startRegistration(BotUser user) {
        clearSession(user);
        repository.saveSession(user.maxUserId(), "REG_CONTACT", Jsons.object());
        sendToUser(user.maxUserId(), """
                📝 *Заявка на доступ*

                Для отправки заявки нужен контактный номер. После этого администратор сам назначит роль и, если нужно, пункт приёмки.
                """, listOf(
                Keyboards.contact("📲 Поделиться контактом"),
                Keyboards.callback("🏠 В меню", "nav:home")
        ));
    }

    private void askForContact(BotUser user) {
        ObjectNode data = editableData(repository.getSession(user.maxUserId()));
        repository.saveSession(user.maxUserId(), "REG_CONTACT", data);
        sendToUser(user.maxUserId(), """
                📱 *Контакт для связи*

                Поделитесь контактом через кнопку ниже. Это обязательный шаг для отправки заявки.
                """, listOf(
                Keyboards.contact("📲 Поделиться контактом"),
                Keyboards.callback("🏠 В меню", "nav:home")
        ));
    }

    private void onRegistrationContact(BotUser user, JsonNode attachments) {
        String phone = extractPhone(attachments);
        if (phone == null) {
            sendToUser(user.maxUserId(), """
                    Я жду контакт из кнопки под сообщением.

                    Чтобы отправить заявку, нажмите кнопку «Поделиться контактом».
                    """, listOf(
                    Keyboards.contact("📲 Поделиться контактом"),
                    Keyboards.callback("🏠 В меню", "nav:home")
            ));
            return;
        }
        repository.setUserPhone(user.id(), phone);
        ObjectNode data = editableData(repository.getSession(user.maxUserId()));
        data.put("phone", phone);
        repository.saveSession(user.maxUserId(), "REG_CONTACT", data);
        finalizeRegistration(user);
    }

    private void finalizeRegistration(BotUser user) {
        PendingRegistration request = repository.createOrUpdateRegistration(user.id(), null, null, null);
        notifyAdminsAboutRegistration(request);
        clearSession(user);
        sendToUser(user.maxUserId(), Texts.registrationCreated(), pendingButtons());
    }

    private void notifyAdminsAboutRegistration(PendingRegistration request) {
        for (BotUser admin : repository.listAdmins()) {
            sendToUser(admin.maxUserId(), Texts.adminRegistrationCard(request), listOf(
                    Keyboards.callback("👀 Открыть заявку", "admin:req:view:" + request.id()),
                    Keyboards.callback("🛡️ Панель администратора", "nav:home")
            ));
        }
    }

    private void startReceiptFlow(BotUser user) {
        if (user.role() != UserRole.EMPLOYEE || user.receivingPointId() == null) {
            sendToUser(user.maxUserId(), """
                    Приёмка доступна только сотруднику, которому назначен пункт.

                    Если профиль настроен неправильно, администратор сможет быстро поправить роль или пункт.
                    """, homeButtons(user));
            return;
        }
        clearSession(user);
        ObjectNode data = Jsons.object();
        data.put("point_id", user.receivingPointId());
        data.put("delivery_date", LocalDate.now(zoneId).toString());
        openReceiptFarmSelection(user, data);
    }

    private void startAdminReceiptFlow(BotUser admin) {
        if (!admin.role().isAdmin()) {
            sendToUser(admin.maxUserId(), "Этот раздел доступен только администратору.", homeButtons(admin));
            return;
        }
        clearSession(admin);
        ObjectNode data = Jsons.object();
        data.put("delivery_date", LocalDate.now(zoneId).toString());
        repository.saveSession(admin.maxUserId(), "RECEIPT_POINT_SELECT", data);

        List<ObjectNode> buttons = new ArrayList<>();
        for (ReceivingPoint point : repository.listPoints()) {
            buttons.add(Keyboards.callback("📍 Пункт: " + point.name(), "receipt:point:id:" + point.id()));
        }
        buttons.add(Keyboards.callback("🏠 В меню", "nav:cancel"));
        sendToUser(admin.maxUserId(), "Выберите пункт приёмки.", buttons);
    }

    private void onReceiptPointChosen(BotUser user, String pointIdRaw) {
        long pointId = parseLong(pointIdRaw);
        ObjectNode data = editableData(repository.getSession(user.maxUserId()));
        data.put("point_id", pointId);
        if (!data.has("delivery_date")) {
            data.put("delivery_date", LocalDate.now(zoneId).toString());
        }
        openReceiptFarmSelection(user, data);
    }

    private void openReceiptFarmSelection(BotUser user, ObjectNode data) {
        repository.saveSession(user.maxUserId(), "RECEIPT_FARM", data);
        List<ObjectNode> buttons = new ArrayList<>();
        for (Farm farm : repository.listFarms(true)) {
            buttons.add(Keyboards.callback("🌾 " + farm.name(), "receipt:farm:" + farm.id()));
        }
        buttons.add(Keyboards.callback("🏠 В меню", "nav:cancel"));
        sendToUser(user.maxUserId(), """
                🥛 *Новая приёмка молока*

                Шаг 1 из 5. Выберите колхоз-поставщик. Если один и тот же поставщик приехал в двух секциях, просто оформите две отдельные записи.
                """, buttons);
    }

    private void onReceiptFarmChosen(BotUser user, String farmIdRaw) {
        long farmId = parseLong(farmIdRaw);
        ObjectNode data = editableData(repository.getSession(user.maxUserId()));
        data.put("farm_id", farmId);
        data.put("section_label", "Без секции");
        if (isDraftEditField(data, "farm")) {
            finishDraftEdit(user, data);
            return;
        }
        repository.saveSession(user.maxUserId(), "RECEIPT_WEIGHT", data);
        sendToUser(user.maxUserId(), """
                ⚖️ *Шаг 2 из 5*

                Введите вес в килограммах. Можно использовать точку или запятую.
                Пример: `12450.0`
                """, listOf(Keyboards.callback("🏠 Отменить и выйти", "nav:cancel")));
    }

    private void onLegacyReceiptSectionInput(BotUser user, String text) {
        ObjectNode data = editableData(repository.getSession(user.maxUserId()));
        data.put("section_label", "Без секции");
        repository.saveSession(user.maxUserId(), "RECEIPT_WEIGHT", data);
        onReceiptWeight(user, text);
    }

    private void onReceiptWeight(BotUser user, String text) {
        Double value = parseDouble(text);
        if (value == null || value <= 0) {
            sendToUser(user.maxUserId(), "Вес должен быть положительным числом. Попробуйте ещё раз.", listOf(
                    Keyboards.callback("🏠 Отменить и выйти", "nav:cancel")
            ));
            return;
        }
        ObjectNode data = editableData(repository.getSession(user.maxUserId()));
        data.put("weight_kg", value);
        if (isDraftEditField(data, "weight") || isDraftEditField(data, "section")) {
            data.remove("draft_edit_field");
            finishDraftEdit(user, data);
            return;
        }
        repository.saveSession(user.maxUserId(), "RECEIPT_FAT", data);
        sendToUser(user.maxUserId(), """
                🧪 *Шаг 3 из 5*

                Введите показатель жира в процентах.
                Пример: `3.62`
                """, listOf(Keyboards.callback("🏠 Отменить и выйти", "nav:cancel")));
    }

    private void onReceiptFat(BotUser user, String text) {
        Double value = parsePercent(text);
        if (value == null) {
            sendToUser(user.maxUserId(), "Жир нужно указать числом от 0 до 10. Попробуйте ещё раз.", listOf(
                    Keyboards.callback("🏠 Отменить и выйти", "nav:cancel")
            ));
            return;
        }
        ObjectNode data = editableData(repository.getSession(user.maxUserId()));
        data.put("fat_percent", value);
        if (isDraftEditField(data, "fat")) {
            finishDraftEdit(user, data);
            return;
        }
        repository.saveSession(user.maxUserId(), "RECEIPT_PROTEIN", data);
        sendToUser(user.maxUserId(), """
                🧬 *Шаг 4 из 5*

                Введите показатель белка в процентах.
                Пример: `3.08`
                """, listOf(Keyboards.callback("🏠 Отменить и выйти", "nav:cancel")));
    }

    private void onReceiptProtein(BotUser user, String text) {
        Double value = parsePercent(text);
        if (value == null) {
            sendToUser(user.maxUserId(), "Белок нужно указать числом от 0 до 10. Попробуйте ещё раз.", listOf(
                    Keyboards.callback("🏠 Отменить и выйти", "nav:cancel")
            ));
            return;
        }
        ObjectNode data = editableData(repository.getSession(user.maxUserId()));
        data.put("protein_percent", value);
        if (isDraftEditField(data, "protein")) {
            finishDraftEdit(user, data);
            return;
        }
        repository.saveSession(user.maxUserId(), "RECEIPT_PHOTO", data);
        sendToUser(user.maxUserId(), """
                📷 *Шаг 5 из 5*

                Прикрепите фотографию накладной. Я постараюсь проверить размер и ориентацию, а затем покажу превью перед сохранением.

                Если фото совсем нельзя приложить сейчас, можно временно сохранить запись без него — она будет отмечена для внимания.
                """, listOf(
                Keyboards.callback("⚠️ Сохранить без фото", "receipt:skip-photo"),
                Keyboards.callback("🏠 Отменить и выйти", "nav:cancel")
        ));
    }

    private void onReceiptPhoto(BotUser user, JsonNode attachments, JsonNode body) {
        Optional<PhotoService.ProcessedPhoto> photo = findImageAttachment(attachments)
                .flatMap(photoService::processIncomingImage);
        if (photo.isEmpty()) {
            sendToUser(user.maxUserId(), "Я жду именно фотографию накладной. Прикрепите изображение или используйте кнопку сохранения без фото.", listOf(
                    Keyboards.callback("⚠️ Сохранить без фото", "receipt:skip-photo"),
                    Keyboards.callback("🏠 Отменить и выйти", "nav:cancel")
            ));
            return;
        }
        ObjectNode data = editableData(repository.getSession(user.maxUserId()));
        data.put("photo_token", photo.get().token());
        data.put("photo_payload_json", photo.get().payloadJson());
        if (photo.get().width() != null) {
            data.put("photo_width", photo.get().width());
        }
        if (photo.get().height() != null) {
            data.put("photo_height", photo.get().height());
        }
        data.put("photo_status", photo.get().status());
        String originalMessageId = body.path("mid").asText("");
        if (!originalMessageId.isBlank()) {
            data.put("original_message_id", originalMessageId);
        }
        if (isDraftEditField(data, "photo")) {
            finishDraftEdit(user, data);
            return;
        }
        repository.saveSession(user.maxUserId(), "RECEIPT_CONFIRM", data);
        sendReceiptConfirmation(user, data);
    }

    private void sendReceiptConfirmation(BotUser user, ObjectNode data) {
        Farm farm = repository.findFarm(data.path("farm_id").asLong()).orElseThrow();
        ReceivingPoint point = repository.findPoint(data.path("point_id").asLong()).orElseThrow();
        double weight = data.path("weight_kg").asDouble();
        double fat = data.path("fat_percent").asDouble();
        double protein = data.path("protein_percent").asDouble();
        data.put("credit_weight_kg", weight);
        repository.saveSession(user.maxUserId(), "RECEIPT_CONFIRM", data);

        String text = """
                🧾 *Проверьте запись перед сохранением*

                Колхоз: *%s*
                Пункт: *%s*
                Вес: *%s кг*
                Жир: *%s%%*
                Белок: *%s%%*
                Фото: *%s*

                Если всё верно, сохраните запись. Если нет — быстро поправим нужный пункт.
                """.formatted(
                farm.name(),
                point.name(),
                Numbers.oneDecimal(weight),
                Numbers.twoDecimals(fat),
                Numbers.twoDecimals(protein),
                data.path("photo_status").asText("MISSING")
        );
        List<ObjectNode> buttons = listOf(
                Keyboards.callback("✅ Сохранить запись", "receipt:confirm"),
                Keyboards.callback("✅ Сохранить и ещё запись", "receipt:confirm:continue"),
                Keyboards.callback("🌾 Изменить колхоз", "receipt:edit:farm"),
                Keyboards.callback("⚖️ Изменить вес", "receipt:edit:weight"),
                Keyboards.callback("🧪 Изменить жир", "receipt:edit:fat"),
                Keyboards.callback("🧬 Изменить белок", "receipt:edit:protein"),
                Keyboards.callback("📷 Заменить фото", "receipt:edit:photo"),
                Keyboards.callback("🏠 Отменить и выйти", "nav:cancel")
        );
        rememberButtonActions(user.maxUserId(), buttons);
        sendToUser(user.maxUserId(), text, Attachments.imageWithKeyboard(
                textOrNull(data, "photo_payload_json"),
                Keyboards.inline(buttons)
        ));
    }

    private void onReceiptDraftEdit(BotUser user, String field) {
        ConversationSession session = repository.getSession(user.maxUserId());
        ObjectNode data = editableData(session);
        switch (field) {
            case "farm" -> {
                data.put("draft_edit_field", field);
                repository.saveSession(user.maxUserId(), "RECEIPT_FARM", data);
                List<ObjectNode> buttons = new ArrayList<>();
                for (Farm farm : repository.listFarms(true)) {
                    buttons.add(Keyboards.callback("🌾 " + farm.name(), "receipt:farm:" + farm.id()));
                }
                buttons.add(Keyboards.callback("🏠 Отменить и выйти", "nav:cancel"));
                sendToUser(user.maxUserId(), "Выберите новый колхоз для черновика записи.", buttons);
            }
            case "weight" -> {
                data.put("draft_edit_field", field);
                repository.saveSession(user.maxUserId(), "RECEIPT_WEIGHT", data);
                sendToUser(user.maxUserId(), "Введите новый вес в килограммах.", listOf(Keyboards.callback("🏠 Отменить и выйти", "nav:cancel")));
            }
            case "fat" -> {
                data.put("draft_edit_field", field);
                repository.saveSession(user.maxUserId(), "RECEIPT_FAT", data);
                sendToUser(user.maxUserId(), "Введите новый показатель жира.", listOf(Keyboards.callback("🏠 Отменить и выйти", "nav:cancel")));
            }
            case "protein" -> {
                data.put("draft_edit_field", field);
                repository.saveSession(user.maxUserId(), "RECEIPT_PROTEIN", data);
                sendToUser(user.maxUserId(), "Введите новый показатель белка.", listOf(Keyboards.callback("🏠 Отменить и выйти", "nav:cancel")));
            }
            case "photo" -> {
                data.put("draft_edit_field", field);
                repository.saveSession(user.maxUserId(), "RECEIPT_PHOTO", data);
                sendToUser(user.maxUserId(), "Прикрепите новую фотографию накладной.", listOf(
                        Keyboards.callback("⚠️ Сохранить без фото", "receipt:skip-photo"),
                        Keyboards.callback("🏠 Отменить и выйти", "nav:cancel")
                ));
            }
            default -> sendToUser(user.maxUserId(), "Такой пункт редактирования не найден. Возвращаю подтверждение записи.", homeButtons(user));
        }
    }

    private void saveReceipt(BotUser user) {
        saveReceipt(user, false);
    }

    private void saveReceiptAndContinueSameFarm(BotUser user) {
        saveReceipt(user, true);
    }

    private void saveReceipt(BotUser user, boolean continueSameFarm) {
        ObjectNode data = editableData(repository.getSession(user.maxUserId()));
        MilkReceipt receipt = repository.createReceipt(
                user.id(),
                data.path("point_id").asLong(),
                data.path("farm_id").asLong(),
                data.path("section_label").asText(),
                LocalDate.parse(data.path("delivery_date").asText(LocalDate.now(zoneId).toString())),
                data.path("weight_kg").asDouble(),
                data.path("fat_percent").asDouble(),
                data.path("protein_percent").asDouble(),
                data.path("credit_weight_kg").asDouble(),
                textOrNull(data, "photo_token"),
                textOrNull(data, "photo_payload_json"),
                data.has("photo_width") ? data.get("photo_width").asInt() : null,
                data.has("photo_height") ? data.get("photo_height").asInt() : null,
                data.path("photo_status").asText("MISSING"),
                textOrNull(data, "original_message_id"),
                null
        );
        if (continueSameFarm) {
            continueReceiptWithSameFarm(user, data, receipt);
            return;
        }
        clearSession(user);
        sendToUser(user.maxUserId(), Texts.receiptSaved(receipt), listOf(
                Keyboards.callback("🥛 Новая приёмка", "receipt:new"),
                Keyboards.callback("🧾 Мои записи", "view:my_receipts"),
                Keyboards.callback("🏠 Главное меню", "nav:home")
        ));
    }

    private void continueReceiptWithSameFarm(BotUser user, ObjectNode previousData, MilkReceipt receipt) {
        Farm farm = repository.findFarm(previousData.path("farm_id").asLong()).orElseThrow();
        ReceivingPoint point = repository.findPoint(previousData.path("point_id").asLong()).orElseThrow();

        ObjectNode nextData = Jsons.object();
        nextData.put("point_id", point.id());
        nextData.put("farm_id", farm.id());
        nextData.put("section_label", "Без секции");
        nextData.put("delivery_date", previousData.path("delivery_date").asText(LocalDate.now(zoneId).toString()));
        repository.saveSession(user.maxUserId(), "RECEIPT_WEIGHT", nextData);

        sendToUser(user.maxUserId(), """
                ✅ *Запись сохранена*

                Номер записи: *%s*
                Продолжаем новую приёмку для колхоза *%s* на пункте *%s*.

                ⚖️ Введите вес в килограммах для следующей записи.
                """.formatted(
                receipt.publicId(),
                farm.name(),
                point.name()
        ), listOf(
                Keyboards.callback("🌾 Изменить колхоз", "receipt:edit:farm"),
                Keyboards.callback("🏠 Отменить и выйти", "nav:cancel")
        ));
    }

    private void showMyReceipts(BotUser user) {
        showMyReceipts(user, 0);
    }

    private void showMyReceipts(BotUser user, int page) {
        int safePage = Math.max(page, 0);
        int total = repository.countReceiptsForUser(user.id());
        if (total > 0 && safePage * RECEIPTS_PAGE_SIZE >= total) {
            safePage = 0;
        }
        List<MilkReceipt> receipts = repository.listReceiptsForUserPage(user.id(), RECEIPTS_PAGE_SIZE, safePage * RECEIPTS_PAGE_SIZE);
        if (receipts.isEmpty()) {
            sendToUser(user.maxUserId(), """
                    Пока у вас нет сохранённых записей.

                    Как только оформите первую приёмку, она появится здесь.
                    """, listOf(
                    Keyboards.callback("🥛 Оформить приёмку", "receipt:new"),
                    Keyboards.callback("🏠 Главное меню", "nav:home")
            ));
            return;
        }
        List<ObjectNode> buttons = new ArrayList<>();
        for (MilkReceipt receipt : receipts) {
            buttons.add(Keyboards.callback(
                    "🧾 " + Dates.formatDate(receipt.deliveryDate()) + " • " + receipt.farmName(),
                    "view:receipt:" + receipt.id()
            ));
        }
        appendPageButtons(buttons, safePage, total, RECEIPTS_PAGE_SIZE, "view:my_receipts:page:");
        buttons.add(Keyboards.callback("🏠 Главное меню", "nav:home"));
        sendToUser(user.maxUserId(), "🧾 *Ваши последние записи*\n\nВыберите запись, чтобы открыть карточку и при необходимости отредактировать её.", buttons);
    }

    private void showFarmsDirectory(BotUser user) {
        StringBuilder text = new StringBuilder("📚 *Справочник колхозов*\n\n");
        repository.listFarms(false).forEach(farm -> text.append("• ").append(farm.name())
                .append(farm.active() ? " — активен" : " — скрыт администратором")
                .append("\n"));
        sendToUser(user.maxUserId(), text.toString(), homeButtons(user));
    }

    private void showReceipt(BotUser user, long receiptId) {
        MilkReceipt receipt = repository.findReceiptById(receiptId).orElseThrow();
        List<ObjectNode> buttons = new ArrayList<>();
        if (canEditReceipt(user, receipt)) {
            buttons.add(Keyboards.callback("⚖️ Изменить вес", "edit:receipt:" + receipt.id() + ":weight"));
            buttons.add(Keyboards.callback("🧪 Изменить жир", "edit:receipt:" + receipt.id() + ":fat"));
            buttons.add(Keyboards.callback("🧬 Изменить белок", "edit:receipt:" + receipt.id() + ":protein"));
            buttons.add(Keyboards.callback("📷 Заменить фото", "edit:receipt:" + receipt.id() + ":photo"));
        }
        if (user.role().isAdmin()) {
            buttons.add(Keyboards.callback("🔓 Разблокировать на 1 час", "admin:record:unlock:" + receipt.id()));
            buttons.add(Keyboards.callback("🗑️ Удалить запись", "admin:record:delete:" + receipt.id()));
        }
        buttons.add(Keyboards.callback("🏠 Главное меню", "nav:home"));
        rememberButtonActions(user.maxUserId(), buttons);
        sendToUser(user.maxUserId(), Texts.receiptDetails(receipt, zoneId), Attachments.imageWithKeyboard(receipt.photoPayloadJson(), Keyboards.inline(buttons)));
    }

    private void beginReceiptEdit(BotUser user, String payload) {
        String[] parts = payload.split(":");
        long receiptId = parseLong(parts[2]);
        String field = parts[3];
        MilkReceipt receipt = repository.findReceiptById(receiptId).orElseThrow();
        if (!canEditReceipt(user, receipt)) {
            sendToUser(user.maxUserId(), """
                    ⛔ Время самостоятельного редактирования уже истекло.

                    Администратор всё ещё может открыть запись заново или внести правку вручную.
                    """, homeButtons(user));
            return;
        }
        ObjectNode data = Jsons.object();
        data.put("receipt_id", receiptId);
        data.put("field", field);
        if ("photo".equals(field)) {
            repository.saveSession(user.maxUserId(), "EDIT_PHOTO_INPUT", data);
            sendToUser(user.maxUserId(), "Прикрепите новую фотографию накладной для записи " + receipt.publicId() + ".", listOf(
                    Keyboards.callback("🏠 Отменить и выйти", "nav:home")
            ));
            return;
        }
        repository.saveSession(user.maxUserId(), "EDIT_FIELD_INPUT", data);
        sendToUser(user.maxUserId(), switch (field) {
            case "weight" -> "Введите новый вес в килограммах для записи " + receipt.publicId() + ".";
            case "fat" -> "Введите новый показатель жира для записи " + receipt.publicId() + ".";
            case "protein" -> "Введите новый показатель белка для записи " + receipt.publicId() + ".";
            default -> "Введите новое значение.";
        }, listOf(Keyboards.callback("🏠 Отменить и выйти", "nav:home")));
    }

    private void onReceiptFieldEditInput(BotUser user, String text) {
        ConversationSession session = repository.getSession(user.maxUserId());
        long receiptId = session.data().path("receipt_id").asLong();
        String field = session.data().path("field").asText();
        MilkReceipt receipt = repository.findReceiptById(receiptId).orElseThrow();
        if (!canEditReceipt(user, receipt)) {
            clearSession(user);
            sendToUser(user.maxUserId(), "Запись уже недоступна для редактирования. Возвращаю в меню.", homeButtons(user));
            return;
        }
        double weight = receipt.weightKg();
        double fat = receipt.fatPercent();
        double protein = receipt.proteinPercent();

        switch (field) {
            case "weight" -> {
                Double value = parseDouble(text);
                if (value == null || value <= 0) {
                    sendToUser(user.maxUserId(), "Вес должен быть положительным числом. Попробуйте ещё раз.", listOf(Keyboards.callback("🏠 В меню", "nav:home")));
                    return;
                }
                weight = value;
            }
            case "fat" -> {
                Double value = parsePercent(text);
                if (value == null) {
                    sendToUser(user.maxUserId(), "Жир нужно указать числом от 0 до 10. Попробуйте ещё раз.", listOf(Keyboards.callback("🏠 В меню", "nav:home")));
                    return;
                }
                fat = value;
            }
            case "protein" -> {
                Double value = parsePercent(text);
                if (value == null) {
                    sendToUser(user.maxUserId(), "Белок нужно указать числом от 0 до 10. Попробуйте ещё раз.", listOf(Keyboards.callback("🏠 В меню", "nav:home")));
                    return;
                }
                protein = value;
            }
            default -> {
                sendToUser(user.maxUserId(), "Поле для редактирования не найдено.", homeButtons(user));
                clearSession(user);
                return;
            }
        }

        repository.updateReceipt(
                receipt.id(),
                user.id(),
                receipt.farmId(),
                receipt.sectionLabel(),
                weight,
                fat,
                protein,
                weight,
                receipt.photoToken(),
                receipt.photoPayloadJson(),
                receipt.photoWidth(),
                receipt.photoHeight(),
                receipt.photoStatus(),
                receipt.note(),
                receipt.adminOverrideUnlockedUntil()
        );
        clearSession(user);
        sendToUser(user.maxUserId(), "✅ Изменения сохранены. Открываю обновлённую карточку записи.", (ArrayNode) null);
        showReceipt(user, receipt.id());
    }

    private void onReceiptPhotoEditInput(BotUser user, JsonNode attachments) {
        ConversationSession session = repository.getSession(user.maxUserId());
        long receiptId = session.data().path("receipt_id").asLong();
        MilkReceipt receipt = repository.findReceiptById(receiptId).orElseThrow();
        if (!canEditReceipt(user, receipt)) {
            clearSession(user);
            sendToUser(user.maxUserId(), "Запись уже недоступна для редактирования. Возвращаю в меню.", homeButtons(user));
            return;
        }

        Optional<PhotoService.ProcessedPhoto> photo = findImageAttachment(attachments)
                .flatMap(photoService::processIncomingImage);
        if (photo.isEmpty()) {
            sendToUser(user.maxUserId(), "Я жду фотографию. Прикрепите изображение накладной ещё раз.", listOf(Keyboards.callback("🏠 В меню", "nav:home")));
            return;
        }

        repository.updateReceipt(
                receipt.id(),
                user.id(),
                receipt.farmId(),
                receipt.sectionLabel(),
                receipt.weightKg(),
                receipt.fatPercent(),
                receipt.proteinPercent(),
                receipt.weightKg(),
                photo.get().token(),
                photo.get().payloadJson(),
                photo.get().width(),
                photo.get().height(),
                photo.get().status(),
                receipt.note(),
                receipt.adminOverrideUnlockedUntil()
        );
        clearSession(user);
        sendToUser(user.maxUserId(), "✅ Фото заменено. Показываю обновлённую карточку.", (ArrayNode) null);
        showReceipt(user, receipt.id());
    }

    private void showPendingRequests(BotUser user) {
        if (!user.role().isAdmin()) {
            sendToUser(user.maxUserId(), "Этот раздел доступен только администратору.", homeButtons(user));
            return;
        }
        List<PendingRegistration> requests = repository.listPendingRegistrations();
        if (requests.isEmpty()) {
            sendToUser(user.maxUserId(), "📭 *Новых заявок нет*\n\nСейчас очередь пуста.", adminButtons());
            return;
        }
        List<ObjectNode> buttons = new ArrayList<>();
        for (PendingRegistration request : requests) {
            buttons.add(Keyboards.callback("🆕 " + request.displayName(), "admin:req:view:" + request.id()));
        }
        buttons.add(Keyboards.callback("🏠 Главное меню", "nav:home"));
        sendToUser(user.maxUserId(), "👥 *Заявки на доступ*\n\nВыберите заявку, чтобы назначить роль и подтвердить доступ.", buttons);
    }

    private void showPendingRequestDetails(BotUser admin, long requestId) {
        if (!admin.role().isAdmin()) {
            sendToUser(admin.maxUserId(), "Этот раздел доступен только администратору.", homeButtons(admin));
            return;
        }
        PendingRegistration request = repository.findPendingRegistration(requestId).orElseThrow();
        sendToUser(admin.maxUserId(), Texts.adminRegistrationCard(request), listOf(
                Keyboards.callback("👨‍🔧 Сделать сотрудником", "admin:req:approve:" + request.id() + ":EMPLOYEE"),
                Keyboards.callback("👔 Сделать директором", "admin:req:approve:" + request.id() + ":DIRECTOR"),
                Keyboards.callback("🏢 Сделать гендиректором", "admin:req:approve:" + request.id() + ":GENERAL_DIRECTOR"),
                Keyboards.callback("🛡️ Сделать администратором", "admin:req:approve:" + request.id() + ":ADMINISTRATOR"),
                Keyboards.callback("❌ Отклонить", "admin:req:reject:" + request.id()),
                Keyboards.callback("🏠 Назад в панель", "nav:home")
        ));
    }

    private void approveRegistration(BotUser admin, String payloadTail) {
        if (!admin.role().isAdmin()) {
            sendToUser(admin.maxUserId(), "Этот раздел доступен только администратору.", homeButtons(admin));
            return;
        }
        String[] parts = payloadTail.split(":");
        long requestId = parseLong(parts[0]);
        UserRole role = UserRole.valueOf(parts[1]);
        if (role == UserRole.EMPLOYEE) {
            List<ObjectNode> buttons = new ArrayList<>();
            for (ReceivingPoint point : repository.listPoints()) {
                buttons.add(Keyboards.callback("📍 Пункт: " + point.name(), "admin:req:point:" + requestId + ":" + point.id()));
            }
            buttons.add(Keyboards.callback("🏠 В панель", "nav:home"));
            sendToUser(admin.maxUserId(), "Выберите пункт приёмки.", buttons);
            return;
        }
        BotUser target = repository.findUserByRegistrationRequestId(requestId).orElse(null);
        repository.approveRegistration(requestId, role, null);
        notifyUserApproved(target, role);
        sendToUser(admin.maxUserId(), "✅ Заявка одобрена. Доступ открыт.", adminButtons());
    }

    private void approveRegistrationWithPoint(BotUser admin, String payloadTail) {
        if (!admin.role().isAdmin()) {
            sendToUser(admin.maxUserId(), "Этот раздел доступен только администратору.", homeButtons(admin));
            return;
        }
        String[] parts = payloadTail.split(":");
        long requestId = parseLong(parts[0]);
        long pointId = parseLong(parts[1]);
        BotUser target = repository.findUserByRegistrationRequestId(requestId).orElse(null);
        repository.approveRegistration(requestId, UserRole.EMPLOYEE, pointId);
        notifyUserApproved(target, UserRole.EMPLOYEE);
        sendToUser(admin.maxUserId(), "✅ Сотрудник подтверждён и привязан к пункту.", adminButtons());
    }

    private void rejectRegistration(BotUser admin, long requestId) {
        if (!admin.role().isAdmin()) {
            sendToUser(admin.maxUserId(), "Этот раздел доступен только администратору.", homeButtons(admin));
            return;
        }
        repository.rejectRegistration(requestId);
        sendToUser(admin.maxUserId(), "❌ Заявка отклонена.", adminButtons());
    }

    private void notifyUserApproved(BotUser user, UserRole role) {
        if (user == null) {
            return;
        }
        BotUser refreshed = repository.findUserByMaxId(user.maxUserId()).orElse(user);
        sendToUser(refreshed.maxUserId(), """
                ✅ Доступ подтвержден

                Бот доступен для работы
                """, homeButtons(refreshed));
    }

    private void showUsersAdmin(BotUser admin) {
        showUsersAdmin(admin, 0);
    }

    private void showUsersAdmin(BotUser admin, int page) {
        if (!admin.role().isAdmin()) {
            sendToUser(admin.maxUserId(), "Этот раздел доступен только администратору.", homeButtons(admin));
            return;
        }
        int safePage = Math.max(page, 0);
        int total = repository.countUsers();
        if (total > 0 && safePage * USERS_PAGE_SIZE >= total) {
            safePage = 0;
        }
        List<ObjectNode> buttons = new ArrayList<>();
        for (BotUser user : repository.listUsersPage(USERS_PAGE_SIZE, safePage * USERS_PAGE_SIZE)) {
            buttons.add(Keyboards.callback((user.active() ? "👤 " : "🚫 ") + user.displayName() + " • " + user.role(), "admin:user:view:" + user.id()));
        }
        appendPageButtons(buttons, safePage, total, USERS_PAGE_SIZE, "admin:users:page:");
        buttons.add(Keyboards.callback("🏠 Главное меню", "nav:home"));
        sendToUser(admin.maxUserId(), "👥 *Пользователи системы*\n\nВыберите человека, чтобы изменить роль, пункт или активность.", buttons);
    }

    private void showUserAdminCard(BotUser admin, long userId) {
        if (!admin.role().isAdmin()) {
            sendToUser(admin.maxUserId(), "Этот раздел доступен только администратору.", homeButtons(admin));
            return;
        }
        BotUser target = repository.findUserById(userId).orElseThrow();
        String pointName = target.receivingPointId() == null ? "не назначен" : repository.findPoint(target.receivingPointId()).map(ReceivingPoint::name).orElse("не найден");
        String firstName = target.firstName() == null || target.firstName().isBlank() ? "не указано" : target.firstName();
        String lastName = target.lastName() == null || target.lastName().isBlank() ? "не указана" : target.lastName();
        String username = target.username() == null || target.username().isBlank() ? "не указан" : "@" + target.username().replaceFirst("^@", "");
        String phone = target.phone() == null || target.phone().isBlank() ? "не указан" : target.phone();
        List<ObjectNode> buttons = new ArrayList<>();
        buttons.add(Keyboards.callback("✏️ Редактировать", "admin:user:edit:" + target.id()));
        buttons.add(Keyboards.callback("👨‍🔧 Сделать сотрудником", "admin:user:role:" + target.id() + ":EMPLOYEE"));
        buttons.add(Keyboards.callback("👔 Сделать директором", "admin:user:role:" + target.id() + ":DIRECTOR"));
        buttons.add(Keyboards.callback("🏢 Сделать гендиректором", "admin:user:role:" + target.id() + ":GENERAL_DIRECTOR"));
        buttons.add(Keyboards.callback("🛡️ Сделать администратором", "admin:user:role:" + target.id() + ":ADMINISTRATOR"));
        if (target.role() == UserRole.EMPLOYEE) {
            for (ReceivingPoint point : repository.listPoints()) {
                buttons.add(Keyboards.callback("📍 Пункт: " + point.name(), "admin:user:point:" + target.id() + ":" + point.id()));
            }
        }
        buttons.add(Keyboards.callback(target.active() ? "🚫 Деактивировать" : "✅ Активировать", "admin:user:toggle:" + target.id()));
        buttons.add(Keyboards.callback("🏠 Назад в панель", "nav:home"));
        sendToUser(admin.maxUserId(), """
                👤 *Карточка пользователя*

                Профиль: *%s*
                Имя: *%s*
                Фамилия: *%s*
                Username: *%s*
                Телефон: *%s*
                MAX ID: `%s`
                Роль: *%s*
                Пункт: *%s*
                Активность: *%s*
                Получает сводку: *%s*
                """.formatted(
                target.displayName(),
                firstName,
                lastName,
                username,
                phone,
                target.maxUserId(),
                target.role(),
                pointName,
                target.active() ? "активен" : "отключён",
                target.dailyDigestEnabled() ? "да" : "нет"
        ), buttons);
    }

    private void openUserEditMenu(BotUser admin, long userId) {
        if (!admin.role().isAdmin()) {
            sendToUser(admin.maxUserId(), "Этот раздел доступен только администратору.", homeButtons(admin));
            return;
        }
        BotUser target = repository.findUserById(userId).orElseThrow();
        sendToUser(admin.maxUserId(), """
                ✏️ *Редактирование карточки*

                Выберите поле, которое нужно изменить. После этого отправьте новое значение одним сообщением.

                Если поле нужно очистить, отправьте символ `-`.
                """, listOf(
                Keyboards.callback("👤 Имя", "admin:user:editfield:" + target.id() + ":first_name"),
                Keyboards.callback("👥 Фамилия", "admin:user:editfield:" + target.id() + ":last_name"),
                Keyboards.callback("📞 Телефон", "admin:user:editfield:" + target.id() + ":phone"),
                Keyboards.callback("@ Username", "admin:user:editfield:" + target.id() + ":username"),
                Keyboards.callback("🏠 К карточке", "admin:user:view:" + target.id())
        ));
    }

    private void beginUserFieldEdit(BotUser admin, String payloadTail) {
        if (!admin.role().isAdmin()) {
            sendToUser(admin.maxUserId(), "Этот раздел доступен только администратору.", homeButtons(admin));
            return;
        }
        String[] parts = payloadTail.split(":");
        long userId = parseLong(parts[0]);
        String field = parts[1];
        repository.findUserById(userId).orElseThrow();

        ObjectNode data = Jsons.object();
        data.put("user_id", userId);
        data.put("field", field);
        repository.saveSession(admin.maxUserId(), "ADMIN_USER_EDIT_INPUT", data);

        String prompt = switch (field) {
            case "first_name" -> "Введите новое имя пользователя.";
            case "last_name" -> "Введите новую фамилию пользователя.";
            case "phone" -> "Введите новый номер телефона пользователя.";
            case "username" -> "Введите новый username пользователя. Можно с символом @ или без него.";
            default -> "Введите новое значение.";
        };
        sendToUser(admin.maxUserId(), prompt + "\n\nДля очистки поля отправьте `-`.", listOf(
                Keyboards.callback("🏠 К карточке", "admin:user:view:" + userId),
                Keyboards.callback("🏠 В меню", "nav:home")
        ));
    }

    private void onAdminUserEditInput(BotUser admin, String text) {
        if (!admin.role().isAdmin()) {
            clearSession(admin);
            sendToUser(admin.maxUserId(), "Этот раздел доступен только администратору.", homeButtons(admin));
            return;
        }
        ConversationSession session = repository.getSession(admin.maxUserId());
        long userId = session.data().path("user_id").asLong();
        String field = session.data().path("field").asText();
        BotUser target = repository.findUserById(userId).orElseThrow();

        String value = normalizeUserEditValue(field, text);
        if (value == null && !"-".equals(text.trim())) {
            sendToUser(admin.maxUserId(), switch (field) {
                case "phone" -> "Телефон не должен быть пустым. Введите номер или `-`, чтобы очистить поле.";
                case "username" -> "Username не должен быть пустым. Введите значение или `-`, чтобы очистить поле.";
                case "first_name" -> "Имя не должно быть пустым. Введите значение или `-`, чтобы очистить поле.";
                case "last_name" -> "Фамилия не должна быть пустой. Введите значение или `-`, чтобы очистить поле.";
                default -> "Введите корректное значение или `-`, чтобы очистить поле.";
            }, listOf(
                    Keyboards.callback("🏠 К карточке", "admin:user:view:" + userId),
                    Keyboards.callback("🏠 В меню", "nav:home")
            ));
            return;
        }

        repository.updateUserEditableField(target.id(), field, value);
        clearSession(admin);
        sendToUser(admin.maxUserId(), "✅ Карточка пользователя обновлена.", (ArrayNode) null);
        showUserAdminCard(admin, target.id());
    }

    private void changeUserRole(BotUser admin, String payloadTail) {
        if (!admin.role().isAdmin()) {
            sendToUser(admin.maxUserId(), "Этот раздел доступен только администратору.", homeButtons(admin));
            return;
        }
        String[] parts = payloadTail.split(":");
        long userId = parseLong(parts[0]);
        UserRole role = UserRole.valueOf(parts[1]);
        if (role == UserRole.EMPLOYEE) {
            List<ObjectNode> buttons = new ArrayList<>();
            for (ReceivingPoint point : repository.listPoints()) {
                buttons.add(Keyboards.callback("📍 Пункт: " + point.name(), "admin:user:point:" + userId + ":" + point.id()));
            }
            buttons.add(Keyboards.callback("🏠 Назад", "admin:users"));
            sendToUser(admin.maxUserId(), "Выберите пункт приёмки.", buttons);
            return;
        }
        repository.setUserRoleAndPoint(userId, role, null);
        sendToUser(admin.maxUserId(), "✅ Роль обновлена.", adminButtons());
    }

    private void changeUserPoint(BotUser admin, String payloadTail) {
        if (!admin.role().isAdmin()) {
            sendToUser(admin.maxUserId(), "Этот раздел доступен только администратору.", homeButtons(admin));
            return;
        }
        String[] parts = payloadTail.split(":");
        long userId = parseLong(parts[0]);
        long pointId = parseLong(parts[1]);
        repository.setUserRoleAndPoint(userId, UserRole.EMPLOYEE, pointId);
        sendToUser(admin.maxUserId(), "✅ Пункт сотрудника обновлён.", adminButtons());
    }

    private void toggleUserActive(BotUser admin, long userId) {
        if (!admin.role().isAdmin()) {
            sendToUser(admin.maxUserId(), "Этот раздел доступен только администратору.", homeButtons(admin));
            return;
        }
        BotUser target = repository.findUserById(userId).orElseThrow();
        repository.setUserActive(target.id(), !target.active());
        sendToUser(admin.maxUserId(), "✅ Статус пользователя обновлён.", adminButtons());
    }

    private void showFarmAdmin(BotUser admin) {
        if (!admin.role().isAdmin()) {
            sendToUser(admin.maxUserId(), "Этот раздел доступен только администратору.", homeButtons(admin));
            return;
        }
        List<ObjectNode> buttons = new ArrayList<>();
        for (Farm farm : repository.listFarms(false)) {
            buttons.add(Keyboards.callback((farm.active() ? "🌾 " : "🚫 ") + farm.name(), "admin:farm:toggle:" + farm.id()));
        }
        buttons.add(Keyboards.callback("➕ Добавить колхоз", "admin:farm:add"));
        buttons.add(Keyboards.callback("🏠 Главное меню", "nav:home"));
        sendToUser(admin.maxUserId(), """
                🌾 *Управление колхозами*

                Нажмите на колхоз, чтобы включить или скрыть его из рабочих списков. Новые названия можно добавлять прямо здесь.
                """, buttons);
    }

    private void askNewFarmName(BotUser admin) {
        if (!admin.role().isAdmin()) {
            sendToUser(admin.maxUserId(), "Этот раздел доступен только администратору.", homeButtons(admin));
            return;
        }
        repository.saveSession(admin.maxUserId(), "ADMIN_ADD_FARM", Jsons.object());
        sendToUser(admin.maxUserId(), """
                ➕ *Новый колхоз*

                Отправьте название одним сообщением. Я сразу добавлю его в рабочий справочник.
                """, listOf(Keyboards.callback("🏠 Отменить и выйти", "nav:home")));
    }

    private void onNewFarmName(BotUser admin, String text) {
        if (!admin.role().isAdmin()) {
            sendToUser(admin.maxUserId(), "Этот раздел доступен только администратору.", homeButtons(admin));
            return;
        }
        if (text.isBlank()) {
            sendToUser(admin.maxUserId(), "Название не должно быть пустым. Отправьте его текстом.", listOf(Keyboards.callback("🏠 В меню", "nav:home")));
            return;
        }
        repository.addFarm(text.trim());
        clearSession(admin);
        sendToUser(admin.maxUserId(), "✅ Колхоз добавлен в справочник.", adminButtons());
    }

    private void toggleFarmActive(BotUser admin, long farmId) {
        if (!admin.role().isAdmin()) {
            sendToUser(admin.maxUserId(), "Этот раздел доступен только администратору.", homeButtons(admin));
            return;
        }
        Farm farm = repository.findFarm(farmId).orElseThrow();
        repository.setFarmActive(farmId, !farm.active());
        sendToUser(admin.maxUserId(), "✅ Статус колхоза обновлён.", adminButtons());
    }

    private void showAdminRecordDateMenu(BotUser admin) {
        if (!admin.role().isAdmin()) {
            sendToUser(admin.maxUserId(), "Этот раздел доступен только администратору.", homeButtons(admin));
            return;
        }
        sendToUser(admin.maxUserId(), """
                ✏️ *Управление записями*

                Можно открыть записи за сегодня, за вчера или ввести дату вручную.
                """, listOf(
                Keyboards.callback("📅 Сегодня", "admin:records:date:" + LocalDate.now(zoneId)),
                Keyboards.callback("📆 Вчера", "admin:records:date:" + LocalDate.now(zoneId).minusDays(1)),
                Keyboards.callback("⌨️ Ввести дату", "admin:records:date:custom"),
                Keyboards.callback("🏠 Главное меню", "nav:home")
        ));
    }

    private void showAdminRecordsForDate(BotUser admin, String dateRaw) {
        showAdminRecordsForDate(admin, dateRaw, 0);
    }

    private void showAdminRecordsForDate(BotUser admin, String dateRaw, int page) {
        if (!admin.role().isAdmin()) {
            sendToUser(admin.maxUserId(), "Этот раздел доступен только администратору.", homeButtons(admin));
            return;
        }
        if (dateRaw.contains(":page:")) {
            String[] parts = dateRaw.split(":page:");
            showAdminRecordsForDate(admin, parts[0], parseIntSafe(parts[1], 0));
            return;
        }
        if ("custom".equals(dateRaw)) {
            repository.saveSession(admin.maxUserId(), "ADMIN_RECORD_DATE", Jsons.object());
            sendToUser(admin.maxUserId(), "Введите дату в формате `дд.ММ.гггг`, чтобы открыть записи.", listOf(
                    Keyboards.callback("🏠 Отменить и выйти", "nav:home")
            ));
            return;
        }
        LocalDate date = LocalDate.parse(dateRaw);
        List<MilkReceipt> receipts = repository.listReceipts(date, date, null, null, false);
        if (receipts.isEmpty()) {
            sendToUser(admin.maxUserId(), "За " + Dates.formatDate(date) + " записей не найдено.", adminButtons());
            return;
        }
        int safePage = Math.max(page, 0);
        int from = safePage * RECORDS_PAGE_SIZE;
        if (from >= receipts.size()) {
            safePage = 0;
            from = 0;
        }
        int to = Math.min(from + RECORDS_PAGE_SIZE, receipts.size());
        List<ObjectNode> buttons = new ArrayList<>();
        for (MilkReceipt receipt : receipts.subList(from, to)) {
            buttons.add(Keyboards.callback("🧾 " + receipt.pointName() + " • " + receipt.farmName(), "admin:record:view:" + receipt.id()));
        }
        appendPageButtons(buttons, safePage, receipts.size(), RECORDS_PAGE_SIZE, "admin:records:date:" + date + ":page:");
        buttons.add(Keyboards.callback("🏠 Главное меню", "nav:home"));
        sendToUser(admin.maxUserId(), "✏️ *Записи за " + Dates.formatDate(date) + "*\n\nВыберите карточку для редактирования или удаления.", buttons);
    }

    private void onAdminRecordDate(BotUser admin, String text) {
        LocalDate date = parseDate(text);
        if (date == null) {
            sendToUser(admin.maxUserId(), "Дата не распознана. Нужен формат `дд.ММ.гггг`.", listOf(Keyboards.callback("🏠 В меню", "nav:home")));
            return;
        }
        clearSession(admin);
        showAdminRecordsForDate(admin, date.toString());
    }

    private void unlockReceipt(BotUser admin, long receiptId) {
        if (!admin.role().isAdmin()) {
            sendToUser(admin.maxUserId(), "Этот раздел доступен только администратору.", homeButtons(admin));
            return;
        }
        repository.unlockReceiptForOneHour(receiptId, admin.id());
        sendToUser(admin.maxUserId(), "🔓 Запись открыта для редактирования ещё на 1 час.", adminButtons());
    }

    private void deleteReceipt(BotUser admin, long receiptId) {
        if (!admin.role().isAdmin()) {
            sendToUser(admin.maxUserId(), "Этот раздел доступен только администратору.", homeButtons(admin));
            return;
        }
        repository.softDeleteReceipt(receiptId, admin.id());
        sendToUser(admin.maxUserId(), "🗑️ Запись убрана из активной базы. Она исключена из аналитики.", adminButtons());
    }

    private void startFarmDayReport(BotUser user) {
        if (!user.role().canViewReports()) {
            sendToUser(user.maxUserId(), "Раздел отчётов доступен только руководящим ролям.", homeButtons(user));
            return;
        }
        ObjectNode data = Jsons.object();
        data.put("report_mode", "FARM_DAY");
        repository.saveSession(user.maxUserId(), "REPORT_FARM_SELECT", data);
        List<ObjectNode> buttons = new ArrayList<>();
        for (Farm farm : repository.listFarms(true)) {
            buttons.add(Keyboards.callback("🌾 " + farm.name(), "report:farmday:farm:" + farm.id()));
        }
        buttons.add(Keyboards.callback("🏠 Главное меню", "nav:home"));
        sendToUser(user.maxUserId(), "🔎 *Выберите колхоз*\n\nПокажу все записи за отдельный день и приложу фотографии накладных.", buttons);
    }

    private void chooseFarmDayReportFarm(BotUser user, long farmId) {
        ObjectNode data = editableData(repository.getSession(user.maxUserId()));
        data.put("report_mode", "FARM_DAY");
        data.put("farm_id", farmId);
        repository.saveSession(user.maxUserId(), "REPORT_DAY_CHOOSE", data);
        sendToUser(user.maxUserId(), """
                📅 *Выберите день*

                Можно открыть сегодня, вчера или указать дату вручную.
                """, listOf(
                Keyboards.callback("📅 Сегодня", "report:day:today"),
                Keyboards.callback("📆 Вчера", "report:day:yesterday"),
                Keyboards.callback("⌨️ Ввести дату", "report:day:custom"),
                Keyboards.callback("🏠 Главное меню", "nav:home")
        ));
    }

    private void onSingleDayChoice(BotUser user, String value) {
        ObjectNode data = editableData(repository.getSession(user.maxUserId()));
        if ("custom".equals(value)) {
            repository.saveSession(user.maxUserId(), "REPORT_CUSTOM_START", data);
            sendToUser(user.maxUserId(), "Введите дату в формате `дд.ММ.гггг`.", listOf(Keyboards.callback("🏠 В меню", "nav:home")));
            return;
        }
        LocalDate date = "today".equals(value) ? LocalDate.now(zoneId) : LocalDate.now(zoneId).minusDays(1);
        sendFarmDayReport(user, data.path("farm_id").asLong(), date);
        clearSession(user);
    }

    private void startPointReport(BotUser user) {
        if (!user.role().canViewReports()) {
            sendToUser(user.maxUserId(), "Раздел отчётов доступен только руководящим ролям.", homeButtons(user));
            return;
        }
        ObjectNode data = Jsons.object();
        data.put("report_mode", "POINT");
        repository.saveSession(user.maxUserId(), "REPORT_POINT_SELECT", data);
        List<ObjectNode> buttons = new ArrayList<>();
        for (ReceivingPoint point : repository.listPoints()) {
            buttons.add(Keyboards.callback("🏭 Пункт: " + point.name(), "report:point:id:" + point.id()));
        }
        buttons.add(Keyboards.callback("🏠 Главное меню", "nav:home"));
        sendToUser(user.maxUserId(), "🏭 *Выберите пункт приёмки*\n\nПокажу сводку за день или за период с разбивкой по колхозам.", buttons);
    }

    private void choosePointForReport(BotUser user, long pointId) {
        ObjectNode data = editableData(repository.getSession(user.maxUserId()));
        data.put("report_mode", "POINT");
        data.put("point_id", pointId);
        repository.saveSession(user.maxUserId(), "REPORT_PERIOD_CHOOSE", data);
        sendPeriodChooser(user);
    }

    private void startGlobalReport(BotUser user) {
        if (!user.role().canViewReports()) {
            sendToUser(user.maxUserId(), "Раздел отчётов доступен только руководящим ролям.", homeButtons(user));
            return;
        }
        ObjectNode data = Jsons.object();
        data.put("report_mode", "GLOBAL");
        repository.saveSession(user.maxUserId(), "REPORT_PERIOD_CHOOSE", data);
        sendPeriodChooser(user);
    }

    private void startExcelReport(BotUser user) {
        if (!user.role().canViewReports()) {
            sendToUser(user.maxUserId(), "Раздел отчётов доступен только руководящим ролям.", homeButtons(user));
            return;
        }
        ObjectNode data = Jsons.object();
        data.put("report_mode", "EXCEL");
        repository.saveSession(user.maxUserId(), "REPORT_FARM_SELECT", data);
        List<ObjectNode> buttons = new ArrayList<>();
        for (Farm farm : repository.listFarms(true)) {
            buttons.add(Keyboards.callback("🌾 " + farm.name(), "report:excel:farm:" + farm.id()));
        }
        buttons.add(Keyboards.callback("🏠 Главное меню", "nav:home"));
        sendToUser(user.maxUserId(), """
                📈 *Excel и графики*

                Выберите колхоз. Я соберу файл с данными и простыми графиками по среднему весу, жиру и белку за период.
                """, buttons);
    }

    private void chooseFarmForExcel(BotUser user, long farmId) {
        ObjectNode data = editableData(repository.getSession(user.maxUserId()));
        data.put("report_mode", "EXCEL");
        data.put("farm_id", farmId);
        repository.saveSession(user.maxUserId(), "REPORT_PERIOD_CHOOSE", data);
        sendPeriodChooser(user);
    }

    private void sendPeriodChooser(BotUser user) {
        sendToUser(user.maxUserId(), """
                ⏱️ *Выберите период*

                Доступны короткие и длинные интервалы, а также произвольный диапазон дат.
                """, listOf(
                Keyboards.callback("📅 Сегодня", "report:period:today"),
                Keyboards.callback("📆 Неделя", "report:period:week"),
                Keyboards.callback("🗓️ 2 недели", "report:period:2week"),
                Keyboards.callback("🗓️ 3 недели", "report:period:3week"),
                Keyboards.callback("🗓️ Месяц", "report:period:month"),
                Keyboards.callback("📘 Квартал", "report:period:quarter"),
                Keyboards.callback("📚 Год", "report:period:year"),
                Keyboards.callback("⌨️ Свой диапазон", "report:period:custom"),
                Keyboards.callback("🏠 Главное меню", "nav:home")
        ));
    }

    private void onPeriodChoice(BotUser user, String periodKey) {
        ObjectNode data = editableData(repository.getSession(user.maxUserId()));
        if ("custom".equals(periodKey)) {
            repository.saveSession(user.maxUserId(), "REPORT_CUSTOM_START", data);
            sendToUser(user.maxUserId(), "Введите дату начала в формате `дд.ММ.гггг`.", listOf(Keyboards.callback("🏠 В меню", "nav:home")));
            return;
        }
        LocalDate end = LocalDate.now(zoneId);
        LocalDate start = switch (periodKey) {
            case "today" -> end;
            case "week" -> end.minusDays(6);
            case "2week" -> end.minusDays(13);
            case "3week" -> end.minusDays(20);
            case "month" -> end.minusMonths(1).plusDays(1);
            case "quarter" -> end.minusMonths(3).plusDays(1);
            case "year" -> end.minusYears(1).plusDays(1);
            default -> end;
        };
        sendReportBySession(user, data, start, end);
        clearSession(user);
    }

    private void onCustomStartDate(BotUser user, String text) {
        LocalDate date = parseDate(text);
        if (date == null) {
            sendToUser(user.maxUserId(), "Дата не распознана. Нужен формат `дд.ММ.гггг`.", listOf(Keyboards.callback("🏠 В меню", "nav:home")));
            return;
        }
        ObjectNode data = editableData(repository.getSession(user.maxUserId()));
        data.put("custom_start", date.toString());
        if ("FARM_DAY".equals(data.path("report_mode").asText())) {
            sendFarmDayReport(user, data.path("farm_id").asLong(), date);
            clearSession(user);
            return;
        }
        repository.saveSession(user.maxUserId(), "REPORT_CUSTOM_END", data);
        sendToUser(user.maxUserId(), "Введите дату окончания в формате `дд.ММ.гггг`.", listOf(Keyboards.callback("🏠 В меню", "nav:home")));
    }

    private void onCustomEndDate(BotUser user, String text) {
        LocalDate end = parseDate(text);
        if (end == null) {
            sendToUser(user.maxUserId(), "Дата не распознана. Нужен формат `дд.ММ.гггг`.", listOf(Keyboards.callback("🏠 В меню", "nav:home")));
            return;
        }
        ObjectNode data = editableData(repository.getSession(user.maxUserId()));
        LocalDate start = LocalDate.parse(data.path("custom_start").asText());
        if (end.isBefore(start)) {
            sendToUser(user.maxUserId(), "Дата окончания не может быть раньше даты начала. Попробуйте ещё раз.", listOf(Keyboards.callback("🏠 В меню", "nav:home")));
            return;
        }
        sendReportBySession(user, data, start, end);
        clearSession(user);
    }

    private void sendReportBySession(BotUser user, ObjectNode data, LocalDate start, LocalDate end) {
        String mode = data.path("report_mode").asText();
        switch (mode) {
            case "POINT" -> sendToUser(user.maxUserId(), reportService.buildPointPeriodReport(data.path("point_id").asLong(), start, end), reportResultButtons());
            case "GLOBAL" -> sendToUser(user.maxUserId(), reportService.buildGlobalPeriodReport(start, end), reportResultButtons());
            case "EXCEL" -> sendExcelReport(user, data.path("farm_id").asLong(), start, end);
            default -> sendToUser(user.maxUserId(), "Не удалось определить тип отчёта. Возвращаю в меню.", homeButtons(user));
        }
    }

    private void sendFarmDayReport(BotUser user, long farmId, LocalDate date) {
        List<MilkReceipt> receipts = repository.listReceipts(date, date, null, farmId, false);
        sendToUser(user.maxUserId(), reportService.buildFarmDayReport(farmId, date), reportResultButtons());
        for (MilkReceipt receipt : receipts) {
            if (receipt.photoPayloadJson() != null && !receipt.photoPayloadJson().isBlank()) {
                sendToUser(user.maxUserId(), """
                        📷 *Накладная*
                        %s • %s
                        """.formatted(receipt.pointName(), receipt.farmName()), Attachments.imageWithKeyboard(receipt.photoPayloadJson(), null));
            }
        }
    }

    private void sendExcelReport(BotUser user, long farmId, LocalDate start, LocalDate end) {
        Path file = reportService.buildExcelFarmReport(farmId, start, end);
        JsonNode uploadPayload = maxApiClient.uploadLocalFile(file, "file");
        List<ObjectNode> buttons = listOf(Keyboards.callback("🏠 Главное меню", "nav:home"));
        rememberButtonActions(user.maxUserId(), buttons);
        sendToUser(user.maxUserId(), """
                📈 *Excel-отчёт готов*

                Внутри файл с таблицей и простыми графиками по среднему весу, жиру и белку за выбранный период.
                """, Attachments.fileWithKeyboard(uploadPayload, Keyboards.inline(buttons)));
    }

    private void toggleDigest(BotUser user) {
        boolean next = !user.dailyDigestEnabled();
        repository.setDailyDigestEnabled(user.id(), next);
        sendToUser(user.maxUserId(), next
                        ? "📬 Ежедневная сводка в конце смены включена."
                        : "📭 Ежедневная сводка в конце смены отключена.",
                homeButtons(repository.findUserByMaxId(user.maxUserId()).orElse(user)));
    }

    private boolean canEditReceipt(BotUser user, MilkReceipt receipt) {
        if (user.role().isAdmin()) {
            return true;
        }
        if (user.id() != receipt.createdByUserId()) {
            return false;
        }
        Instant now = Instant.now();
        if (now.isBefore(receipt.editableUntil())) {
            return true;
        }
        return receipt.adminOverrideUnlockedUntil() != null && now.isBefore(receipt.adminOverrideUnlockedUntil());
    }

    private void safeAnswerCallback(String callbackId, String notification) {
        try {
            maxApiClient.answerCallback(callbackId, notification);
        } catch (Exception e) {
            log.warn("Failed to answer callback {}", callbackId, e);
        }
    }

    private Optional<JsonNode> findImageAttachment(JsonNode attachments) {
        if (attachments == null || !attachments.isArray()) {
            return Optional.empty();
        }
        for (JsonNode attachment : attachments) {
            if ("image".equalsIgnoreCase(attachment.path("type").asText())) {
                return Optional.of(attachment);
            }
        }
        return Optional.empty();
    }

    private String extractPhone(JsonNode attachments) {
        if (attachments == null || !attachments.isArray()) {
            return null;
        }
        for (JsonNode attachment : attachments) {
            if ("contact".equalsIgnoreCase(attachment.path("type").asText())) {
                String vcf = attachment.path("payload").path("vcf_info").asText("");
                Matcher matcher = PHONE_PATTERN.matcher(vcf);
                if (matcher.find()) {
                    return matcher.group(1);
                }
            }
        }
        return null;
    }

    private long extractMaxUserId(JsonNode update) {
        long userId = firstNonZero(
                update.path("user").path("user_id").asLong(),
                update.path("callback").path("user").path("user_id").asLong(),
                update.path("callback").path("sender").path("user_id").asLong(),
                update.path("message").path("sender").path("user_id").asLong(),
                update.path("message").path("recipient").path("user_id").asLong()
        );
        return userId;
    }

    private Long extractChatId(JsonNode update) {
        long chatId = update.path("chat_id").asLong();
        return chatId == 0L ? null : chatId;
    }

    private String extractUsername(JsonNode update) {
        return firstNonBlank(
                update.path("user").path("username").asText(null),
                update.path("callback").path("user").path("username").asText(null),
                update.path("callback").path("sender").path("username").asText(null),
                update.path("message").path("sender").path("username").asText(null)
        );
    }

    private String extractFirstName(JsonNode update) {
        return firstNonBlank(
                update.path("user").path("first_name").asText(null),
                update.path("user").path("name").asText(null),
                update.path("callback").path("user").path("first_name").asText(null),
                update.path("callback").path("user").path("name").asText(null),
                update.path("callback").path("sender").path("first_name").asText(null),
                update.path("callback").path("sender").path("name").asText(null),
                update.path("message").path("sender").path("first_name").asText(null),
                update.path("message").path("sender").path("name").asText(null)
        );
    }

    private String extractLastName(JsonNode update) {
        return firstNonBlank(
                update.path("user").path("last_name").asText(null),
                update.path("callback").path("user").path("last_name").asText(null),
                update.path("callback").path("sender").path("last_name").asText(null),
                update.path("message").path("sender").path("last_name").asText(null)
        );
    }

    private boolean isFromBot(JsonNode update) {
        return update.path("user").path("is_bot").asBoolean(false)
                || update.path("callback").path("user").path("is_bot").asBoolean(false)
                || update.path("callback").path("sender").path("is_bot").asBoolean(false)
                || update.path("message").path("sender").path("is_bot").asBoolean(false);
    }

    private void clearSession(BotUser user) {
        repository.saveSession(user.maxUserId(), "IDLE", Jsons.object());
    }

    private ArrayNode homeButtons(BotUser user) {
        return switch (user.role()) {
            case PENDING -> unknownButtons();
            case EMPLOYEE -> employeeButtons();
            case DIRECTOR, GENERAL_DIRECTOR -> directorButtons();
            case ADMINISTRATOR -> adminButtons();
        };
    }

    private ArrayNode unknownButtons() {
        return Keyboards.inline(listOf(
                Keyboards.callback("🚀 Начать", "start"),
                Keyboards.callback("📝 Подать заявку", "reg:apply")
        ));
    }

    private ArrayNode pendingButtons() {
        return Keyboards.inline(listOf(
                Keyboards.callback("🔄 Обновить статус", "status:refresh"),
                Keyboards.callback("📝 Изменить заявку", "reg:apply")
        ));
    }

    private ArrayNode employeeButtons() {
        return Keyboards.inline(listOf(
                Keyboards.callback("🥛 Принять молоко", "receipt:new"),
                Keyboards.callback("🧾 Мои записи", "view:my_receipts"),
                Keyboards.callback("📚 Справочник колхозов", "directory:farms")
        ));
    }

    private ArrayNode directorButtons() {
        return Keyboards.inline(listOf(
                Keyboards.callback("🔎 Колхоз за день", "report:farmday:start"),
                Keyboards.callback("🏭 Сводка по пункту", "report:point:start"),
                Keyboards.callback("🌍 Сводка по всем пунктам", "report:global:start"),
                Keyboards.callback("📈 Excel и графики", "report:excel:start"),
                Keyboards.callback("📬 Вкл/выкл сводку смены", "digest:toggle")
        ));
    }

    private ArrayNode adminButtons() {
        return Keyboards.inline(listOf(
                Keyboards.callback("👥 Заявки на доступ", "admin:requests"),
                Keyboards.callback("🧑‍💼 Пользователи", "admin:users"),
                Keyboards.callback("🌾 Колхозы", "admin:farms"),
                Keyboards.callback("➕ Добавить приёмку", "admin:receipt:new"),
                Keyboards.callback("✏️ Записи", "admin:records"),
                Keyboards.callback("🔎 Колхоз за день", "report:farmday:start"),
                Keyboards.callback("🏭 Сводка по пункту", "report:point:start"),
                Keyboards.callback("🌍 Сводка по всем пунктам", "report:global:start"),
                Keyboards.callback("📈 Excel и графики", "report:excel:start"),
                Keyboards.callback("📬 Вкл/выкл сводку смены", "digest:toggle")
        ));
    }

    private void sendToUser(long maxUserId, String text, ArrayNode attachments) {
        if (attachments == null) {
            clearButtonActions(maxUserId);
        }
        OutgoingMessage message = containsInlineKeyboard(attachments)
                ? OutgoingMessage.plain(stripMarkdown(text), attachments)
                : OutgoingMessage.markdown(text, attachments);
        maxApiClient.sendToUser(maxUserId, message);
    }

    private void sendToUser(long maxUserId, String text, List<ObjectNode> buttons) {
        rememberButtonActions(maxUserId, buttons);
        sendToUser(maxUserId, text, Keyboards.inline(buttons));
    }

    private List<ObjectNode> reportResultButtons() {
        return listOf(Keyboards.callback("🏠 Главное меню", "nav:home"));
    }

    private ObjectNode editableData(ConversationSession session) {
        return session.data().isObject() ? (ObjectNode) session.data() : Jsons.object();
    }

    private List<ObjectNode> listOf(ObjectNode... buttons) {
        List<ObjectNode> result = new ArrayList<>();
        for (ObjectNode button : buttons) {
            result.add(button);
        }
        return result;
    }

    private long parseLong(String value) {
        return Long.parseLong(value);
    }

    private Double parseDouble(String text) {
        try {
            return Double.parseDouble(text.replace(",", ".").trim());
        } catch (Exception e) {
            return null;
        }
    }

    private Double parsePercent(String text) {
        Double value = parseDouble(text);
        if (value == null || value < 0 || value > 10) {
            return null;
        }
        return value;
    }

    private LocalDate parseDate(String text) {
        try {
            return LocalDate.parse(text.trim(), Dates.DATE);
        } catch (Exception e) {
            return null;
        }
    }

    private String textOrNull(ObjectNode data, String field) {
        return data.hasNonNull(field) ? data.get(field).asText() : null;
    }

    private String normalizeUserEditValue(String field, String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        if (trimmed.equals("-")) {
            return null;
        }
        if (trimmed.isBlank()) {
            return null;
        }
        return switch (field) {
            case "username" -> trimmed.replaceFirst("^@", "");
            case "phone", "first_name", "last_name" -> trimmed;
            default -> trimmed;
        };
    }

    private boolean handleGlobalCommand(BotUser user, String text) {
        if (text == null || text.isBlank() || !text.startsWith("/")) {
            return false;
        }

        String command = text.trim().toLowerCase();
        return switch (command) {
            case "/start" -> {
                sendEntryPoint(user);
                yield true;
            }
            case "/role", "/whoami" -> {
                sendRoleStatus(user);
                yield true;
            }
            case "/switchhelp", "/testhelp" -> {
                sendSwitcherHelp(user);
                yield true;
            }
            case "/admin" -> {
                switchRoleForTesting(user, UserRole.ADMINISTRATOR);
                yield true;
            }
            case "/director" -> {
                switchRoleForTesting(user, UserRole.DIRECTOR);
                yield true;
            }
            case "/gendirector", "/ceo" -> {
                switchRoleForTesting(user, UserRole.GENERAL_DIRECTOR);
                yield true;
            }
            case "/employee", "/user" -> {
                switchRoleForTesting(user, UserRole.EMPLOYEE);
                yield true;
            }
            case "/pending" -> {
                switchRoleForTesting(user, UserRole.PENDING);
                yield true;
            }
            case "/chepas" -> {
                switchPointForTesting(user, "Чепас");
                yield true;
            }
            case "/arat", "/bigar", "/arath" -> {
                switchPointForTesting(user, "Большая Арать");
                yield true;
            }
            case "/pilna" -> {
                switchPointForTesting(user, "Пильна");
                yield true;
            }
            default -> false;
        };
    }

    private void switchRoleForTesting(BotUser user, UserRole role) {
        if (!isTestSwitcherAllowed(user)) {
            sendToUser(user.maxUserId(), """
                    ⛔ Команда тестового переключения ролей недоступна.

                    Она работает только для пользователей, чьи MAX ID указаны в `BOOTSTRAP_ADMIN_USER_IDS`.
                    """, homeButtons(user));
            return;
        }

        Long pointId = switch (role) {
            case EMPLOYEE -> resolveTestingPointId(user);
            case PENDING, DIRECTOR, GENERAL_DIRECTOR, ADMINISTRATOR -> null;
        };

        repository.setUserRoleAndPoint(user.id(), role, pointId);
        clearSession(user);
        BotUser refreshed = repository.findUserByMaxId(user.maxUserId()).orElse(user);

        String pointText = refreshed.receivingPointId() == null
                ? "не назначен"
                : repository.findPoint(refreshed.receivingPointId()).map(ReceivingPoint::name).orElse("не найден");

        sendToUser(refreshed.maxUserId(), """
                🧪 *Тестовая роль переключена*

                Новая роль: *%s*
                Пункт приёмки: *%s*

                Открываю меню этой роли.
                """.formatted(role, pointText), homeButtons(refreshed));
    }

    private void switchPointForTesting(BotUser user, String pointName) {
        if (!isTestSwitcherAllowed(user)) {
            sendToUser(user.maxUserId(), """
                    ⛔ Команда тестового переключения пункта недоступна.

                    Она работает только для пользователей, чьи MAX ID указаны в `BOOTSTRAP_ADMIN_USER_IDS`.
                    """, homeButtons(user));
            return;
        }

        ReceivingPoint point = repository.listPoints().stream()
                .filter(it -> it.name().equalsIgnoreCase(pointName))
                .findFirst()
                .orElseThrow();

        UserRole targetRole = user.role() == UserRole.PENDING ? UserRole.EMPLOYEE : user.role();
        if (targetRole != UserRole.EMPLOYEE) {
            targetRole = UserRole.EMPLOYEE;
        }

        repository.setUserRoleAndPoint(user.id(), targetRole, point.id());
        clearSession(user);
        BotUser refreshed = repository.findUserByMaxId(user.maxUserId()).orElse(user);
        sendToUser(refreshed.maxUserId(), """
                📍 *Тестовый пункт переключён*

                Теперь ваш пункт приёмки: *%s*.
                Для удобства роль автоматически приведена к *EMPLOYEE*, чтобы вы могли сразу тестировать приёмку.
                """.formatted(point.name()), homeButtons(refreshed));
    }

    private void sendRoleStatus(BotUser user) {
        String pointName = user.receivingPointId() == null
                ? "не назначен"
                : repository.findPoint(user.receivingPointId()).map(ReceivingPoint::name).orElse("не найден");
        sendToUser(user.maxUserId(), """
                👤 *Текущий профиль*

                MAX ID: `%s`
                Роль: *%s*
                Пункт приёмки: *%s*
                Доступ к тест-переключению: *%s*
                """.formatted(
                user.maxUserId(),
                user.role(),
                pointName,
                isTestSwitcherAllowed(user) ? "да" : "нет"
        ), homeButtons(user));
    }

    private void sendSwitcherHelp(BotUser user) {
        String extra = isTestSwitcherAllowed(user)
                ? """
                Доступные команды:
                • `/admin`
                • `/director`
                • `/gendirector`
                • `/employee`
                • `/user`
                • `/pending`
                • `/chepas`
                • `/arat`
                • `/pilna`
                • `/role`
                """
                : "Тестовые команды сейчас недоступны для этого профиля.";

        sendToUser(user.maxUserId(), """
                🧪 *Тестирование ролей и пунктов*

                %s
                """.formatted(extra), homeButtons(user));
    }

    private boolean isTestSwitcherAllowed(BotUser user) {
        return config.bootstrapAdminUserIds().contains(user.maxUserId());
    }

    private Long resolveTestingPointId(BotUser user) {
        if (user.receivingPointId() != null) {
            return user.receivingPointId();
        }
        return repository.listPoints().stream().findFirst().map(ReceivingPoint::id).orElse(null);
    }

    private String extractCallbackId(JsonNode update) {
        return firstNonBlank(
                update.path("callback").path("callback_id").asText(null),
                update.path("callback_id").asText(null)
        );
    }

    private String extractCallbackPayload(JsonNode update) {
        JsonNode callbackPayload = update.path("callback").path("payload");
        if (callbackPayload.isTextual() && !callbackPayload.asText().isBlank()) {
            return callbackPayload.asText();
        }
        if (callbackPayload.isObject() && callbackPayload.hasNonNull("payload")) {
            String nestedPayload = callbackPayload.path("payload").asText(null);
            if (nestedPayload != null && !nestedPayload.isBlank()) {
                return nestedPayload;
            }
        }
        String directPayload = update.path("payload").asText(null);
        return directPayload == null || directPayload.isBlank() ? null : directPayload;
    }

    private String resolveButtonAction(BotUser user, String text) {
        if (text == null || text.isBlank()) {
            return null;
        }

        ConversationSession session = repository.getSession(user.maxUserId());
        JsonNode mappedActions = session.data().path("button_actions");
        if (mappedActions.isObject()) {
            String mapped = mappedActions.path(text).asText(null);
            if (mapped != null && !mapped.isBlank()) {
                return mapped;
            }
        }

        return switch (text) {
            case "🚀 Начать" -> "start";
            case "📝 Подать заявку", "📝 Изменить заявку" -> "reg:apply";
            case "🔄 Обновить статус", "🏠 Обновить статус" -> "status:refresh";
            case "🥛 Принять молоко", "🥛 Оформить приёмку", "🥛 Новая приёмка" -> "receipt:new";
            case "🧾 Мои записи" -> "view:my_receipts";
            case "📚 Справочник колхозов" -> "directory:farms";
            case "🔎 Колхоз за день" -> "report:farmday:start";
            case "🏭 Сводка по пункту" -> "report:point:start";
            case "🌍 Сводка по всем пунктам" -> "report:global:start";
            case "📈 Excel и графики" -> "report:excel:start";
            case "📬 Вкл/выкл сводку смены" -> "digest:toggle";
            case "👥 Заявки на доступ" -> "admin:requests";
            case "🧑‍💼 Пользователи" -> "admin:users";
            case "🌾 Колхозы" -> "admin:farms";
            case "➕ Добавить приёмку" -> "admin:receipt:new";
            case "✏️ Записи" -> "admin:records";
            case "⚠️ Сохранить без фото" -> "receipt:skip-photo";
            case "🏠 Главное меню", "🏠 В меню", "🏠 Назад в панель", "🏠 В панель" -> "nav:home";
            case "🏠 Отменить и выйти" -> "nav:cancel";
            default -> null;
        };
    }

    private void rememberButtonActions(long maxUserId, List<ObjectNode> buttons) {
        ConversationSession session = repository.getSession(maxUserId);
        ObjectNode data = editableData(session);
        data.remove("button_actions");

        if (buttons != null && !buttons.isEmpty()) {
            ObjectNode actions = Jsons.object();
            for (ObjectNode button : buttons) {
                String text = button.path("text").asText("");
                String payload = firstNonBlank(
                        button.path("_bot_payload").asText(null),
                        button.path("payload").asText(null)
                );
                if (!text.isBlank() && payload != null && !payload.isBlank()) {
                    actions.put(text, payload);
                }
            }
            if (!actions.isEmpty()) {
                data.set("button_actions", actions);
            }
        }

        repository.saveSession(maxUserId, session.state(), data);
    }

    private void clearButtonActions(long maxUserId) {
        ConversationSession session = repository.getSession(maxUserId);
        ObjectNode data = editableData(session);
        if (!data.has("button_actions")) {
            return;
        }
        data.remove("button_actions");
        repository.saveSession(maxUserId, session.state(), data);
    }

    private void appendPageButtons(List<ObjectNode> buttons, int page, int totalItems, int pageSize, String payloadPrefix) {
        int totalPages = Math.max(1, (int) Math.ceil((double) totalItems / pageSize));
        if (page > 0) {
            buttons.add(Keyboards.callback("⬅️ Назад", payloadPrefix + (page - 1)));
        }
        if (page + 1 < totalPages) {
            buttons.add(Keyboards.callback("➡️ Далее", payloadPrefix + (page + 1)));
        }
    }

    private boolean isDraftEditField(ObjectNode data, String field) {
        return field.equals(data.path("draft_edit_field").asText(""));
    }

    private void finishDraftEdit(BotUser user, ObjectNode data) {
        data.remove("draft_edit_field");
        repository.saveSession(user.maxUserId(), "RECEIPT_CONFIRM", data);
        sendReceiptConfirmation(user, data);
    }

    private int parseIntSafe(String raw, int fallback) {
        try {
            return Integer.parseInt(raw);
        } catch (Exception e) {
            return fallback;
        }
    }

    private long firstNonZero(long... values) {
        for (long value : values) {
            if (value != 0L) {
                return value;
            }
        }
        return 0L;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private boolean containsInlineKeyboard(ArrayNode attachments) {
        if (attachments == null) {
            return false;
        }
        for (JsonNode attachment : attachments) {
            if ("inline_keyboard".equalsIgnoreCase(attachment.path("type").asText())) {
                return true;
            }
        }
        return false;
    }

    private String stripMarkdown(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }
        return text
                .replace("*", "")
                .replace("_", "")
                .replace("`", "");
    }
}
