# Forte2Firefly Telegram Bot

Telegram бот для автоматического сохранения фотографий транзакций из приложения Forte в систему управления финансами Firefly III.

## Возможности

- Автоматическое распознавание текста с фотографий транзакций Forte
- Парсинг информации о транзакции (описание, сумма, дата, карта)
- Автоматическое определение валюты основной транзакции
- Извлечение и добавление MCC кода (Merchant Category Code) в теги транзакции
- Сохранение транзакции в Firefly III через API
- Прикрепление оригинальной фотографии к транзакции
- Поддержка мультивалютных транзакций (основная сумма + сумма в MYR)
- Поддержка бюджетов с возможностью переключения между категориями

## Технологии

- **Kotlin** 2.2.20
- **TGBotAPI** 24.0.0 - библиотека для работы с Telegram Bot API
- **Ktor Client** 3.0.3 - HTTP клиент для взаимодействия с Firefly III API
- **Tess4J** 5.15.0 - Java wrapper для Tesseract OCR
- **Kotlinx Serialization** - сериализация JSON

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

### Сборка JAR

```bash
./gradlew build
export DYLD_LIBRARY_PATH=/opt/homebrew/lib:$DYLD_LIBRARY_PATH
export TESSDATA_PREFIX=/opt/homebrew/share/tessdata
export $(cat .env | xargs) && java -jar build/libs/forte2firefly-1.0-SNAPSHOT.jar
```

## Логика валют

- **Основная валюта транзакции**: Определяется автоматически по символу валюты (USD, EUR, GBP и т.д.)
- **Transaction amount**: По умолчанию используется MYR (настраивается через `DEFAULT_CURRENCY`)

Пример:
- Фото показывает: `-18,29 $` и `75.5`
- Бот создаст транзакцию: `18.29 USD` с foreign amount `75.5 MYR`

## OCR Сервис

Бот использует **Tesseract OCR** для распознавания текста с фотографий. Tesseract - это open-source движок OCR.

## Конфигурация Firefly III

Бот создает транзакции со следующими параметрами:
- `type`: "withdrawal" (расход)
- `error_if_duplicate_hash`: true
- `apply_rules`: true
- `fire_webhooks`: true
- `tags`: Автоматически добавляется тег с MCC кодом в формате "mcc:XXXX" (например, "mcc:5912" для аптек)
- `budgetName`: По умолчанию используется бюджет "main" с возможностью переключения

## MCC коды

Бот автоматически извлекает MCC код (Merchant Category Code) из чека Forte и добавляет его в теги транзакции. MCC код - это 4-значный код категории торговца, который помогает классифицировать покупки:
- 5411 - Продуктовые магазины
- 5912 - Аптеки
- 5814 - Рестораны быстрого питания
- 5812 - Рестораны
- И т.д.

Тег добавляется в формате `mcc:XXXX`, что позволяет легко фильтровать и группировать транзакции по категориям в Firefly III.

## Разработка

### Структура кода

- `FireflyApiClient` - HTTP клиент для Firefly III API
- `TransactionParser` - парсинг текста транзакции
- `OCRService` - распознавание текста с помощью Tesseract
- `TelegramBotHandler` - обработка сообщений бота


## Лицензия

MIT

## Автор

CentralHardware
