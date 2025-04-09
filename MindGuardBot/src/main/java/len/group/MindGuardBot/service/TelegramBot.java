package len.group.MindGuardBot.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import len.group.MindGuardBot.config.BotConfig;
import len.group.MindGuardBot.dao.TaskRepository;
import len.group.MindGuardBot.dao.UserRepository;
import len.group.MindGuardBot.helper.ScheduleSession;
import len.group.MindGuardBot.models.Task;
import len.group.MindGuardBot.models.User;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.hibernate.Hibernate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.ParseMode;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboard;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardRow;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;

@Slf4j
@Component
public class TelegramBot extends TelegramLongPollingBot {

    private final Map<Long, TaskState> userStates = new HashMap<>();
    private final Map<Long, Task> pendingTasks = new HashMap<>();

    private enum TaskState {
        AWAITING_DESCRIPTION,
        AWAITING_DATE_SELECTION,
        AWAITING_CUSTOM_DATE
    }

    private final BotConfig botConfig;

    private final UserRepository userRepository;
    private final TaskRepository taskRepository;

    private final Map<Long, ScheduleSession> userSessions = new HashMap<>();

    @Value("${together.api.key}")
    private String togetherApiKey;

    @Autowired
    public TelegramBot(BotConfig botConfig, UserRepository userRepository, TaskRepository taskRepository1) {
        this.userRepository = userRepository;
        this.taskRepository = taskRepository1;
        this.botConfig = botConfig;
    }

    @Override
    public void onUpdateReceived(Update update) {
        if (update.hasMessage() && update.getMessage().hasText()) {
            String text = update.getMessage().getText();
            Long chatId = update.getMessage().getChatId();
            System.out.println(text);

            if (text.equals("/start")) {
                startCommandReceived(chatId, update.getMessage().getChat().getFirstName());
            } else if (text.equals("/reg")) {
                regUser(chatId, update.getMessage().getChat().getFirstName());
            } else if (text.equals("/addtask")) {
                startTaskCreation(chatId);  // 🔹 Интерактивный режим задач
            } else if (text.equals("/mytasks")) {
                handleMyTasks(chatId);
            } else if (text.equals("/gen_schedule")) {
                ScheduleSession session = new ScheduleSession();
                session.setStep(ScheduleSession.ScheduleStep.WAITING_FOR_SCHEDULE);
                userSessions.put(chatId, session);
                sendMessage(chatId, "📅 Пришли своё расписание занятий (в любом виде):");
                return;
            } else {
                // интерактивная генерация расписания
                if (userSessions.containsKey(chatId)) {
                    ScheduleSession session = userSessions.get(chatId);
                    switch (session.getStep()) {
                        case WAITING_FOR_SCHEDULE -> {
                            session.setRawSchedule(text);
                            session.setStep(ScheduleSession.ScheduleStep.WAITING_FOR_TASKS);
                            sendMessage(chatId, "✅ Расписание получено!\n\nТеперь пришли список задач и сколько времени ты хочешь потратить на каждую (например:\n- Домашка по ТАиФЯ — 3ч\n- Курсовая — 5ч)");
                            return;
                        }
                        case WAITING_FOR_TASKS -> {
                            session.setRawTasks(text);
                            sendMessage(chatId, "🔄 Генерирую расписание с помощью нейросети...");

                            String finalSchedule = callAiScheduler(session.getRawSchedule(), session.getRawTasks());

                            sendMessage(chatId, "🧠 Вот твоё сгенерированное расписание:\n\n" + finalSchedule);
                            userSessions.remove(chatId);
                            return;
                        }
                    }
                }
                // интерактивная запись задачи
                TaskState state = userStates.get(chatId);
                if (state != null) {
                    switch (state) {
                        case AWAITING_DESCRIPTION:
                            processTaskDescription(chatId, text);
                            return;
                        case AWAITING_CUSTOM_DATE:
                            processCustomDate(chatId, text);
                            return;
                    }
                }
            }
        } else if (update.hasCallbackQuery()) {
            String callbackData = update.getCallbackQuery().getData();
            Integer messageId = update.getCallbackQuery().getMessage().getMessageId();
            Long chatId = update.getCallbackQuery().getMessage().getChatId();

            log.info("Была нажата кнопка: {}", callbackData);

            if (callbackData.startsWith("SHOW_BUTTON_")) {
                String taskId = callbackData.split("_")[2];
                showTask(chatId, messageId, taskId);
            } else if (callbackData.startsWith("DEL_BUTTON_")) {
                String taskId = callbackData.split("_")[2];
                taskRepository.deleteById(Long.parseLong(taskId));

                DeleteMessage deleteMessage = new DeleteMessage();
                deleteMessage.setChatId(chatId.toString());
                deleteMessage.setMessageId(messageId);

                try {
                    execute(deleteMessage);
                } catch (TelegramApiException e) {
                    log.error("DEL Button вызвало ошибку: {}", e.getMessage());
                }

                handleMyTasks(chatId);
            } else if (callbackData.startsWith("EDIT_BUTTON_")) {
                String taskId = callbackData.split("_")[2];
            } else if (callbackData.startsWith("DATE_")) {
                processTaskDate(chatId, callbackData);
            }
        }
    }

    public String callAiScheduler(String schedule, String tasks) {
        String prompt = String.format("""
        Составь расписание задач по следующей информации.
        
        Вот расписание занятий:
        %s

        Вот список задач с указанием времени:
        %s

        Составь оптимальное расписание на неделю. Не переписывай пары, а только дополняй расписание задачами.
        Ответь красиво и понятно, например:
        Понедельник:
        - 18:00–20:00: Курсовая
        
        Вторник:
        - 17:00–19:00: Подготовка к ТАиФЯ
        """, schedule, tasks);

        try {
            OkHttpClient client = new OkHttpClient();

            MediaType mediaType = MediaType.get("application/json");
            String json = new ObjectMapper().writeValueAsString(Map.of(
                    "model", "meta-llama/Llama-3-70b-chat-hf",
                    "max_tokens", 500,
                    "temperature", 0.7,
                    "messages", List.of(
                            Map.of("role", "user", "content", prompt)
                    )
            ));

            Request request = new Request.Builder()
                    .url("https://api.together.xyz/v1/chat/completions")
                    .post(RequestBody.create(mediaType, json))
                    .addHeader("Authorization", "Bearer " + togetherApiKey)  // или подставь напрямую
                    .addHeader("Content-Type", "application/json")
                    .build();

            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful()) throw new IOException("Ошибка запроса: " + response);

                String body = response.body().string();
                JsonNode root = new ObjectMapper().readTree(body);
                return root.get("choices").get(0).get("message").get("content").asText();
            }

        } catch (Exception e) {
            e.printStackTrace();
            return "❌ Произошла ошибка при генерации расписания.";
        }
    }



    private void startTaskCreation(Long chatId) {
        userStates.put(chatId, TaskState.AWAITING_DESCRIPTION);
        sendMessage(chatId, "📝 Введите описание задачи:");
    }

    private void processTaskDescription(Long chatId, String description) {
        userStates.put(chatId, TaskState.AWAITING_DATE_SELECTION);

        Task task = new Task();
        task.setDescription(description);
        pendingTasks.put(chatId, task);

        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();

        rows.add(Collections.singletonList(createButton("📅 Сегодня", "DATE_TODAY")));
        rows.add(Collections.singletonList(createButton("📆 Завтра", "DATE_TOMORROW")));
        rows.add(Collections.singletonList(createButton("🗓 Выбрать дату", "DATE_CUSTOM")));

        markup.setKeyboard(rows);
        sendMessage(chatId, "Выберите дату выполнения задачи:", markup);
    }

    private InlineKeyboardButton createButton(String text, String callbackData) {
        InlineKeyboardButton button = new InlineKeyboardButton();
        button.setText(text);
        button.setCallbackData(callbackData);
        return button;
    }

    private void processCustomDate(Long chatId, String text) {
        try {
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");
            LocalDateTime deadline = LocalDateTime.parse(text, formatter);

            Task task = pendingTasks.get(chatId);
            if (task != null) {
                saveTask(chatId, task, deadline);
            }
        } catch (DateTimeParseException e) {
            sendMessage(chatId, "❌ Ошибка! Введите дату в формате `DD.MM.YYYY HH:MM`.");
        }
    }

    private void processTaskDate(Long chatId, String callbackData) {
        Task task = pendingTasks.get(chatId);
        if (task == null) return;

        LocalDateTime deadline;
        if (callbackData.equals("DATE_TODAY")) {
            deadline = LocalDateTime.now().withHour(18).withMinute(0);
        } else if (callbackData.equals("DATE_TOMORROW")) {
            deadline = LocalDateTime.now().plusDays(1).withHour(18).withMinute(0);
        } else {
            userStates.put(chatId, TaskState.AWAITING_CUSTOM_DATE);
            sendMessage(chatId, "Введите дату в формате `DD.MM.YYYY HH:MM`:");
            return;
        }

        saveTask(chatId, task, deadline);
    }

    private void saveTask(Long chatId, Task task, LocalDateTime deadline) {
        task.setDeadline(deadline);
        task.setUser(userRepository.findByChatIdWithTasks(chatId));
        task.setCompleted(false);
        taskRepository.save(task);

        pendingTasks.remove(chatId);
        userStates.remove(chatId);

        sendMessage(chatId, "✅ Задача добавлена: " + task.getDescription() +
                "\n📅 Дедлайн: " + deadline.format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")));
    }


    @Transactional
    protected void showTask(Long chatId, Integer messId, String taskId) {
        Task task = taskRepository.findById(Long.parseLong(taskId))
                .orElseThrow( () -> new RuntimeException("Task not found"));
        User user = task.getUser();
        EditMessageText message = new EditMessageText();
        StringBuilder text = new StringBuilder("Ваша задача: \n");
        InlineKeyboardMarkup inlineKeyboardMarkup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        List<InlineKeyboardButton> row = new ArrayList<>();
        var delButton = new InlineKeyboardButton();
        var editButton = new InlineKeyboardButton();
        delButton.setText("\uD83D\uDDD1");
        editButton.setText("\uD83D\uDCDD");

        delButton.setCallbackData("DEL_BUTTON_" + taskId);
        editButton.setCallbackData("EDIT_BUTTON_" + taskId);
        row.add(delButton);
        row.add(editButton);
        rows.add(row);
        
        inlineKeyboardMarkup.setKeyboard(rows);
        text.append(task.getDescription());
        text.append("\n Дедлайн:\n").append(task.getDeadline());
        message.setChatId(chatId);
        message.setText(text.toString());
        message.setMessageId(messId);
        message.setReplyMarkup(inlineKeyboardMarkup);
        try{
            execute(message);
        }catch (TelegramApiException e){
            e.printStackTrace();
        }
    }

    private void regUser(Long chatId, String username) {
        User user = new User();
        user.setChatId(chatId);
        if(userRepository.findByChatId(chatId) != null) sendMessage(chatId,"Вы уже зарегестрированы");
        else {
            user.setFirstName(username);
            userRepository.save(user);
            sendMessage(chatId, "Поздравляю вы зарегестрированы");
        }
    }

//    @Transactional
//    public void handleAddTask(Long chatId, String text) {
//        try {
//            // Формат: /addtask Описание задачи /завтра 18:00
//            String[] parts = text.split("/завтра");
//            log.info(parts[0]);
//            String description = parts[0].replace("/addtask", "").trim();
//            LocalDateTime deadline = LocalDateTime.now()
//                    .plusDays(1)
//                    .withHour(18)
//                    .withMinute(0)
//                    .withSecond(0);
//
//            User user = userRepository.findByChatIdWithTasks(chatId);
//            Task task = new Task();
//            task.setDescription(description);
//            task.setDeadline(deadline);
//            task.setUser(user);
//            task.setCompleted(false);
//            System.out.println();
//            taskRepository.save(task);
//
//            sendMessage(chatId, "✅ Задача добавлена: " + description);
//        } catch (Exception e) {
//            e.printStackTrace();
//            sendMessage(chatId, "❌ Ошибка формата или регистрации( /reg ). Используй: /addtask Описание /завтра 18:00");
//        }
//    }


    private void handleMyTasks(Long chatId) {
        User user = userRepository.findByChatId(chatId);
        List<Task> tasks = taskRepository.getAllByUser(user);

        String text = "Выши задачи:\n";

        InlineKeyboardMarkup inlineKeyboardMarkup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (Task task : tasks) {
            log.info(task.toString());
            List<InlineKeyboardButton> row = new ArrayList<>();
            var button = new InlineKeyboardButton();
            if(task.getDescription().isEmpty()){
                button.setText("Пустая задача");
            }else  button.setText(task.getDescription());
            button.setCallbackData("SHOW_BUTTON_" + task.getId());

            row.add(button);
            rows.add(row);
        }
        inlineKeyboardMarkup.setKeyboard(rows);
        sendMessage(chatId, text, inlineKeyboardMarkup);
    }


    private void startCommandReceived(long chatId, String activeUsernames) {
        String text = "    Привет, это личный бот помощник\n" +
                "Здесь ты можешь записывать задачи которые нужно выполнить.\n" +
                "У тебя есть возможность играть в викторину по Jave отвечать на вопросы и получать за это баллы.\n" +
                "Балы трать на развлечения (Список развлечений и наград ты можешь настроить сам)\n";
        ReplyKeyboardMarkup markup = new ReplyKeyboardMarkup();
        List<KeyboardRow> rows = new ArrayList<>();
        KeyboardRow row = new KeyboardRow();
        row.add("Task");
        row.add("Shop");
        row.add("Java");
        rows.add(row);
        markup.setKeyboard(rows);
        markup.setResizeKeyboard(true);
        sendMessage(chatId, text, markup);
    }

    private void sendMessage(long chatId, String textToSend, ReplyKeyboard markup) {
        SendMessage message = new SendMessage();
        message.setChatId(chatId);
        message.setText(textToSend);
        message.setParseMode(ParseMode.HTML);
        if (markup != null) message.setReplyMarkup(markup);

        try {
            execute(message);
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }
    private void sendMessage(long chatId, String textToSend) {
        SendMessage message = new SendMessage();
        message.setChatId(chatId);
        message.setText(textToSend);
        message.enableHtml(true);
        message.setParseMode(ParseMode.HTML); // Указываем, что используем HTML
        try {
            execute(message);
        }catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }

    @Override
    public String getBotUsername() {
        return botConfig.getBotName();
    }

    @Override
    public String getBotToken() {
        return botConfig.getToken();
    }
}
