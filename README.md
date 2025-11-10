# Forte2Firefly Telegram Bot

Telegram бот для автоматического сохранения фотографий транзакций из приложения Forte в систему управления финансами Firefly III.

## Возможности

- Автоматическое распознавание текста с фотографий транзакций Forte
- Парсинг информации о транзакции (описание, сумма, дата, карта)
- Автоматическое определение валюты основной транзакции
- Сохранение транзакции в Firefly III через API
- Прикрепление оригинальной фотографии к транзакции
- Поддержка мультивалютных транзакций (основная сумма + сумма в MYR)

## Технологии

- **Kotlin** 2.2.20
- **TGBotAPI** 24.0.0 - библиотека для работы с Telegram Bot API
- **Ktor Client** 3.0.3 - HTTP клиент для взаимодействия с Firefly III API
- **Tess4J** 5.15.0 - Java wrapper для Tesseract OCR
- **Kotlinx Serialization** - сериализация JSON
- **Logback** - логирование

## Структура проекта

```
src/main/kotlin/me/centralhardware/forte2firefly/
├── model/
│   ├── FireflyModels.kt       # Модели данных для Firefly III API
│   └── ForteTransaction.kt    # Модель транзакции Forte
├── service/
│   ├── FireflyApiClient.kt    # Клиент для работы с Firefly III API
│   ├── OCRService.kt          # Сервис распознавания текста с Tesseract
│   ├── TransactionParser.kt   # Парсер данных транзакций
│   └── TelegramBotHandler.kt  # Обработчик Telegram бота
└── Main.kt                    # Точка входа приложения
```

## Установка и настройка

### Предварительные требования

1. **JDK 24+**
2. **Tesseract OCR** - должен быть установлен в системе
3. **Telegram Bot Token** (получить у [@BotFather](https://t.me/botfather))
4. **Firefly III instance** и Personal Access Token

### Установка Tesseract OCR

Tesseract должен быть установлен в системе перед запуском бота.

#### macOS
```bash
brew install tesseract
```

#### Ubuntu/Debian
```bash
sudo apt-get update
sudo apt-get install tesseract-ocr
```

#### Windows
Скачайте и установите с официального репозитория:
https://github.com/UB-Mannheim/tesseract/wiki

#### Проверка установки
```bash
tesseract --version
```

Вы должны увидеть информацию о версии Tesseract.

#### Дополнительные языки (опционально)

Для улучшенного распознавания можно установить языковые пакеты:

```bash
# macOS
brew install tesseract-lang

# Ubuntu/Debian
sudo apt-get install tesseract-ocr-eng tesseract-ocr-rus

# Проверка доступных языков
tesseract --list-langs
```

### Настройка проекта

1. Клонируйте репозиторий:
```bash
git clone <repository-url>
cd forte2firefly
```

2. Скопируйте `.env.example` в `.env` и заполните переменные:
```bash
cp .env.example .env
```

3. Отредактируйте `.env`:
```env
TELEGRAM_BOT_TOKEN=your_bot_token_from_botfather
FIREFLY_BASE_URL=https://your-firefly-instance.com
FIREFLY_TOKEN=your_firefly_personal_access_token
DEFAULT_CURRENCY=MYR
```

### Получение токенов

#### Telegram Bot Token
1. Напишите [@BotFather](https://t.me/botfather)
2. Отправьте команду `/newbot`
3. Следуйте инструкциям и сохраните полученный токен

#### Firefly III Token
1. Откройте ваш Firefly III instance
2. Перейдите в Options → Profile → OAuth → Personal Access Tokens
3. Создайте новый токен и сохраните его

## Запуск

### Через IntelliJ IDEA (рекомендуется)

Проект включает готовые Run Configurations для IDEA:

1. Откройте проект в IntelliJ IDEA
2. Выберите нужную конфигурацию из выпадающего списка:
   - **TestOCR** - демо программа для тестирования OCR
   - **Main (Telegram Bot)** - запуск Telegram бота (нужно заполнить токены!)
   - **OCRTest** - запуск unit тестов
3. Нажмите зеленую кнопку Run ▶️

**Важно:** Для "Main (Telegram Bot)" нужно заполнить переменные окружения (Edit Configurations → Environment variables).

Подробнее см. [IDEA_SETUP.md](IDEA_SETUP.md)

### Через Gradle (командная строка)

```bash
export DYLD_LIBRARY_PATH=/opt/homebrew/lib:$DYLD_LIBRARY_PATH
export TESSDATA_PREFIX=/opt/homebrew/share/tessdata
export $(cat .env | xargs) && ./gradlew run
```

### Сборка JAR

```bash
./gradlew build
export DYLD_LIBRARY_PATH=/opt/homebrew/lib:$DYLD_LIBRARY_PATH
export TESSDATA_PREFIX=/opt/homebrew/share/tessdata
export $(cat .env | xargs) && java -jar build/libs/forte2firefly-1.0-SNAPSHOT.jar
```

## Использование

1. Запустите бота
2. Откройте Telegram и найдите вашего бота
3. Отправьте боту фотографию транзакции из Forte
4. Бот автоматически:
   - Распознает текст на фото
   - Извлечёт данные транзакции
   - Создаст транзакцию в Firefly III
   - Прикрепит фото к транзакции
   - Отправит вам подтверждение

## Формат фотографии транзакции

Бот ожидает фотографии со следующей структурой текста:

```
NSK GROCER- QCM
-18,29 $
09 november's 2025 15:37:39
Card Solo Visa Signature MLT **1293
12165085404
75.5
```

Где:
- Строка 1: Описание транзакции
- Строка 2: Сумма и символ валюты
- Строка 3: Дата и время
- Строка 4: Источник (карта)
- Строка 5: Номер транзакции
- Строка 6: Сумма в валюте по умолчанию (MYR)

## Логика валют

- **Основная валюта транзакции**: Определяется автоматически по символу валюты (USD, EUR, GBP и т.д.)
- **Transaction amount**: По умолчанию используется MYR (настраивается через `DEFAULT_CURRENCY`)

Пример:
- Фото показывает: `-18,29 $` и `75.5`
- Бот создаст транзакцию: `18.29 USD` с foreign amount `75.5 MYR`

## OCR Сервис

Бот использует **Tesseract OCR** для распознавания текста с фотографий. Tesseract - это open-source движок OCR, разработанный Google.

### Особенности

- Автоматическое распознавание текста с фотографий транзакций
- **Интеллектуальная предобработка изображений** для улучшения точности:
  - Upscaling (1.5x) для лучшего распознавания мелкого текста
  - Конвертация в grayscale
  - Увеличение контрастности (1.3x)
  - Бинаризация (threshold 128)
- Поддержка множества языков (eng по умолчанию)
- Оптимизированные параметры Tesseract (PageSegMode: 6, OcrEngineMode: 1)

### Настройка

OCRService можно настроить через конструктор:

```kotlin
// Базовая инициализация (язык: английский)
val ocrService = OCRService()

// С указанием пути к tessdata
val ocrService = OCRService(tessdataPath = "/usr/share/tesseract-ocr/4.00/tessdata")

// С указанием языка
val ocrService = OCRService(language = "eng+rus")
```

### Переменные окружения (опционально)

Можно настроить через переменные окружения:

```bash
# Путь к tessdata (опционально, если не найден автоматически)
export TESSDATA_PREFIX=/usr/share/tesseract-ocr/4.00/tessdata

# Язык распознавания (по умолчанию: eng)
export TESSERACT_LANG=eng
```

### Качество распознавания

Бот включает **интеллектуальный парсер** и **улучшенную предобработку изображений**:

✅ **Что работает автоматически:**
- **Предобработка изображений** для улучшения OCR (включена по умолчанию)
- Игнорирование UI элементов (время, иконки)
- Приоритетный поиск отрицательных сумм (расходы)
- Поиск полей по ключевым словам вместо порядка строк
- Fallback механизмы для всех полей
- Оптимальные параметры Tesseract для транзакций

**Точность:** 100% на тестовых данных Forte (см. `OCR_IMPROVEMENTS.md`)

Если качество недостаточно хорошее:
1. Убедитесь, что фото четкое и хорошо освещено
2. Проверьте, что предобработка включена (по умолчанию да)
3. Установите дополнительные языковые пакеты Tesseract
4. См. `OCR_IMPROVEMENTS.md` для тонкой настройки параметров

## Конфигурация Firefly III

Бот создает транзакции со следующими параметрами:
- `type`: "withdrawal" (расход)
- `error_if_duplicate_hash`: true
- `apply_rules`: true
- `fire_webhooks`: true
- `tags`: ["forte", "telegram-bot"]

## Логирование

Логи выводятся в консоль с уровнем INFO. Формат настраивается в `src/main/resources/logback.xml`

## Разработка

### Структура кода

- `FireflyApiClient` - HTTP клиент для Firefly III API
- `TransactionParser` - парсинг текста транзакции
- `OCRService` - распознавание текста с помощью Tesseract
- `TelegramBotHandler` - обработка сообщений бота

### Добавление новых валют

Отредактируйте `TransactionParser.kt:80-92`:

```kotlin
fun detectCurrency(currencySymbol: String): String {
    return when (currencySymbol) {
        "$" -> "USD"
        "€" -> "EUR"
        // Добавьте свою валюту здесь
        else -> "USD"
    }
}
```

## Troubleshooting

### Ошибка "Unable to load library 'tesseract'" при запуске из IDEA
- Используйте готовые Run Configurations из `.idea/runConfigurations/`
- Или настройте переменные окружения вручную (см. [IDEA_SETUP.md](IDEA_SETUP.md)):
  - `DYLD_LIBRARY_PATH=/opt/homebrew/lib`
  - `TESSDATA_PREFIX=/opt/homebrew/share/tessdata`

### Бот не отвечает
- Проверьте правильность `TELEGRAM_BOT_TOKEN`
- Убедитесь, что бот запущен

### Ошибка подключения к Firefly
- Проверьте `FIREFLY_BASE_URL` (должен быть без слэша в конце)
- Проверьте правильность `FIREFLY_TOKEN`
- Убедитесь, что Firefly III доступен

### Не распознается текст
- Убедитесь, что Tesseract установлен: `tesseract --version`
- Проверьте, что фото четкое и хорошо освещено
- Предобработка включена по умолчанию, но можно настроить параметры (см. OCR_IMPROVEMENTS.md)
- Проверьте логи для деталей ошибки

### Ошибка "Failed to initialize Tesseract OCR"
- Убедитесь, что Tesseract установлен в системе
- Проверьте путь к tessdata: `ls /opt/homebrew/share/tessdata/eng.traineddata`
- На macOS: `brew install tesseract`
- На Ubuntu: `sudo apt-get install tesseract-ocr`

## Лицензия

MIT

## Автор

CentralHardware
