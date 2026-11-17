import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.Scanner;
import com.google.gson.*;
import io.github.cdimascio.dotenv.Dotenv;
import io.github.cdimascio.dotenv.DotenvException;

public class Main {
    private static final String FORECAST_URL = "https://api.weather.yandex.ru/v2/forecast";

    public static void main(String[] args) {
        System.out.println("Сервис погоды\n");

        String key;
        try {
            Dotenv dotenv = Dotenv.configure().ignoreIfMissing().load();
            key = dotenv.get("YANDEX_WEATHER_API_KEY");
        } catch (DotenvException e) {
            System.out.println("Не удалось прочитать настройки. Проверьте формат и доступность файла .env.");
            return;
        }
        if (key == null || key.isBlank()) {
            System.out.println("Ключ API не задан. Укажите YANDEX_WEATHER_API_KEY в переменных окружения "
                    + "или скопируйте .env.example в .env и заполните ключ.");
            return;
        }

        Scanner userInput = new Scanner(System.in);
        System.out.println("Введите координаты точки, для которой хотите узнать погоду.");
        System.out.println("Например, Москва: широта 55.75, долгота 37.62.");
        System.out.println("Дробную часть указывайте через точку.\n");

        try {
            double lat = readCoordinate(userInput, "Широта", -90, 90);
            double lon = readCoordinate(userInput, "Долгота", -180, 180);
            int limit = readDayLimit(userInput);

            System.out.println("\nПолучаем данные о погоде…");
            String answer = getWeather(lat, lon, limit, key.trim());
            if (answer != null) {
                printWeather(answer, limit);
            }
        } catch (NoSuchElementException e) {
            System.out.println("\nВвод завершён. Программа остановлена.");
        } catch (HttpTimeoutException e) {
            System.out.println("Сервис погоды не ответил вовремя. Попробуйте позже.");
        } catch (IOException e) {
            System.out.println("Не удалось получить данные о погоде. Проверьте соединение и попробуйте позже.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.out.println("Запрос погоды прерван.");
        }
    }

    static double readCoordinate(Scanner userInput, String name, int min, int max) {
        while (true) {
            System.out.print(name + " (от " + min + " до " + max + "): ");
            String value = userInput.nextLine().trim();
            try {
                double coordinate = Double.parseDouble(value);
                if (Double.isFinite(coordinate) && coordinate >= min && coordinate <= max) {
                    return coordinate;
                }
            } catch (NumberFormatException e) {
                // Повторяем вопрос, если введено не число.
            }
            System.out.println(name + " должна быть числом от " + min + " до " + max
                    + ". Дробную часть указывайте через точку. Повторите ввод.");
        }
    }

    static int readDayLimit(Scanner userInput) {
        while (true) {
            System.out.print("Количество дней прогноза: ");
            String value = userInput.nextLine().trim();
            try {
                int limit = Integer.parseInt(value);
                if (limit > 0) {
                    return limit;
                }
            } catch (NumberFormatException e) {
                // Повторяем вопрос, если введено не целое число.
            }
            System.out.println("Количество дней должно быть целым положительным числом. Повторите ввод.");
        }
    }

    static String getWeather(double lat, double lon, int limit, String key)
            throws IOException, InterruptedException {
        String url = FORECAST_URL
                + "?lat=" + lat
                + "&lon=" + lon
                + "&limit=" + limit;
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("X-Yandex-Weather-Key", key)
                .GET()
                .build();
        HttpResponse<String> response;
        try (HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build()) {
            response = client.send(request, HttpResponse.BodyHandlers.ofString());
        }

        if (response.statusCode() != 200) {
            String message = switch (response.statusCode()) {
                case 401, 403 -> "Сервис отклонил запрос. Проверьте ключ API и права доступа.";
                case 429 -> "Превышен лимит запросов. Попробуйте позже или проверьте ограничения тарифа.";
                default -> "Не удалось получить погоду. Попробуйте позже.";
            };
            System.out.println(message + " HTTP " + response.statusCode() + ".");
            System.out.println("Полный ответ сервиса (JSON):");
            System.out.println(response.body());
            return null;
        }
        return response.body();
    }

    static void printWeather(String answer, int limit) {
        System.out.println("\nПолный ответ сервиса (JSON):");
        System.out.println(answer);
        if (answer.isBlank()) {
            System.out.println("Сервис вернул пустой ответ. Попробуйте позже.");
            return;
        }

        try {
            JsonObject root = JsonParser.parseString(answer).getAsJsonObject();
            double factTemp = extractFactTemp(root);
            System.out.printf(Locale.ROOT, "\nТекущая температура: %.1f °C%n", factTemp);

            ForecastAverage average = averageDayTemp(root, limit);
            if (average.days() == 0) {
                System.out.println("В ответе нет дней прогноза. Среднюю температуру рассчитать нельзя.");
                return;
            }
            if (average.days() < limit) {
                System.out.println("Запрошено дней: " + limit + ", получено: " + average.days()
                        + ". Среднее рассчитано по доступным дням.");
            }
            System.out.printf(Locale.ROOT, "Средняя дневная температура по прогнозу за %d дн.: %.1f °C%n",
                    average.days(), average.temperature());
        } catch (JsonParseException | IllegalStateException | NullPointerException
                 | ClassCastException | NumberFormatException e) {
            System.out.println("Не удалось обработать данные о погоде: ответ содержит некорректный JSON "
                    + "или не содержит необходимых числовых полей температуры.");
        }
    }

    static double extractFactTemp(JsonObject root) {
        return readTemperature(root.getAsJsonObject("fact"), "temp");
    }

    static ForecastAverage averageDayTemp(JsonObject root, int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("Количество дней должно быть положительным.");
        }
        JsonArray forecasts = root.getAsJsonArray("forecasts");
        int days = Math.min(limit, forecasts.size());
        if (days == 0) {
            return new ForecastAverage(0, 0);
        }
        double sum = 0;
        for (int i = 0; i < days; i++) {
            JsonObject forecast = forecasts.get(i).getAsJsonObject();
            JsonObject day = forecast.getAsJsonObject("parts").getAsJsonObject("day");
            sum += readTemperature(day, "temp_avg");
        }
        return new ForecastAverage(sum / days, days);
    }

    static double readTemperature(JsonObject object, String field) {
        JsonPrimitive value = object.getAsJsonPrimitive(field);
        if (value == null || !value.isNumber()) {
            throw new JsonParseException("Отсутствует числовое поле температуры.");
        }
        double temperature = value.getAsDouble();
        if (!Double.isFinite(temperature)) {
            throw new JsonParseException("Температура должна быть конечным числом.");
        }
        return temperature;
    }

    record ForecastAverage(double temperature, int days) {}
}
