package ru.milk.maxbot.util;

import ru.milk.maxbot.domain.BotUser;
import ru.milk.maxbot.domain.MilkReceipt;
import ru.milk.maxbot.domain.PendingRegistration;

import java.time.ZoneId;

public final class Texts {
    private Texts() {
    }

    public static String welcomeUnknown() {
        return """
                🥛 *Добро пожаловать в систему приёмки молока*

                Этот бот помогает аккуратно и быстро вести приёмку по пунктам:
                • Чепас
                • Большая Арать
                • Пильна

                Здесь можно оформить поставку по секциям, сохранить фото накладной, посчитать зачётный вес и получить управленческую сводку без ручных таблиц.

                Нажмите кнопку ниже, чтобы начать работу, или отправьте заявку на доступ.
                """;
    }

    public static String pendingApproval(BotUser user) {
        return """
                ⏳ *Заявка уже отправлена*

                %s, ваш доступ ещё не подтверждён администратором. Как только роль будет назначена, бот сразу откроет рабочее меню.

                Пока можно открыть справку или обновить статус заявки.
                """.formatted(user.displayName());
    }

    public static String employeeHome(BotUser user, String pointName) {
        return """
                👋 *Здравствуйте, %s!*

                Ваша роль: *сотрудник*
                Ваш пункт приёмки: *%s*

                Я помогу быстро оформить новую поставку, посмотреть последние записи и не потеряться в процессе. В каждом шаге можно вернуться назад или выйти в главное меню.
                """.formatted(user.displayName(), pointName);
    }

    public static String managerHome(BotUser user, String roleLabel) {
        return """
                📊 *Здравствуйте, %s!*

                Ваша роль: *%s*

                Здесь доступны детальные записи по колхозам, сводка по пунктам, общие отчёты за период, зачётный вес и выгрузка графиков в Excel.
                """.formatted(user.displayName(), roleLabel);
    }

    public static String adminHome(BotUser user) {
        return """
                🛡️ *Здравствуйте, %s!*

                Вы находитесь в панели администратора. Здесь можно подтверждать новых пользователей, назначать роли и пункты, редактировать записи, управлять списком колхозов и держать систему в порядке.
                """.formatted(user.displayName());
    }

    public static String registrationCreated() {
        return """
                ✅ *Заявка отправлена*

                Спасибо! Я передал администратору информацию на согласование. Как только доступ будет одобрен, бот откроет рабочий кабинет с нужными разделами.
                """;
    }

    public static String receiptSaved(MilkReceipt receipt) {
        return """
                ✅ *Приёмка сохранена*

                Номер записи: *%s*
                Дата: *%s*
                Колхоз: *%s*
                Пункт: *%s*
                Секция: *%s*
                Вес: *%s кг*
                Жир: *%s%%*
                Белок: *%s%%*
                Зачётный вес: *%s кг*

                Запись доступна для редактирования в течение 1 часа. Если потребуется исправление позже, администратор сможет открыть её повторно.
                """.formatted(
                receipt.publicId(),
                Dates.formatDate(receipt.deliveryDate()),
                receipt.farmName(),
                receipt.pointName(),
                receipt.sectionLabel(),
                Numbers.oneDecimal(receipt.weightKg()),
                Numbers.twoDecimals(receipt.fatPercent()),
                Numbers.twoDecimals(receipt.proteinPercent()),
                Numbers.oneDecimal(receipt.creditWeightKg())
        );
    }

    public static String adminRegistrationCard(PendingRegistration request) {
        String point = request.requestedPointName() == null ? "не выбран" : request.requestedPointName();
        String phone = request.phone() == null || request.phone().isBlank() ? "не указан" : request.phone();
        return """
                🆕 *Новая заявка на доступ*

                Сотрудник: *%s*
                MAX ID: `%s`
                Телефон: *%s*
                Желаемая роль: *%s*
                Желаемый пункт: *%s*
                Комментарий: %s
                """.formatted(
                request.displayName(),
                request.maxUserId(),
                phone,
                request.requestedRole(),
                point,
                request.comment() == null || request.comment().isBlank() ? "без комментария" : request.comment()
        );
    }

    public static String receiptDetails(MilkReceipt receipt, ZoneId zoneId) {
        return """
                🧾 *Карточка записи %s*

                Колхоз: *%s*
                Пункт: *%s*
                Секция: *%s*
                Дата поставки: *%s*
                Принял: *%s*
                Вес: *%s кг*
                Жир: *%s%%*
                Белок: *%s%%*
                Зачётный вес: *%s кг*
                Статус фото: *%s*
                Создано: *%s*
                Редактирование до: *%s*
                """.formatted(
                receipt.publicId(),
                receipt.farmName(),
                receipt.pointName(),
                receipt.sectionLabel(),
                Dates.formatDate(receipt.deliveryDate()),
                receipt.createdByName(),
                Numbers.oneDecimal(receipt.weightKg()),
                Numbers.twoDecimals(receipt.fatPercent()),
                Numbers.twoDecimals(receipt.proteinPercent()),
                Numbers.oneDecimal(receipt.creditWeightKg()),
                receipt.photoStatus(),
                Dates.formatDateTime(receipt.createdAt(), zoneId),
                Dates.formatDateTime(receipt.editableUntil(), zoneId)
        );
    }
}
