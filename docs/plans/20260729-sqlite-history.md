# SQLite history

## Overview

Записывать всю собираемую телеметрию в локальную SQLite, чтобы можно было отмотать назад и
разобрать постфактум — «что жрало ресурсы вчера в 3 ночи». Плюс переселить туда же `AlertState`,
который сейчас целиком в памяти и сбрасывается вместе с агентом.

Что это чинит по существу:

- сэмпл живёт ровно до следующего сэмпла: отчёт печатается в лог launchd и перезаписывает
  `Reports/latest.html`, после чего данные исчезают навсегда — вопрос «что было ночью» задать некому;
- `AlertState` умирает вместе с процессом: после рестарта агента алерт, который горел до него,
  считается новым и уведомление уходит повторно, а накопленный backoff по сбоям доставки обнуляется;
- `suppressedAlertKeys` — единственное место, где виден алерт, вытесненный per-category cap, и он
  тоже никуда не сохраняется.

Harmon будет только **писать**. Читает пользователь сам через `sqlite3`, поэтому CLI-команд для
чтения нет, а схема документируется в новом `docs/history.md` — это и есть интерфейс фичи.

Одно исключение из «всей телеметрии», зафиксированное сознательно: `SystemUsage.processIssues`
построчно не хранится. В `sample` едут четыре агрегатных счётчика, которые уже есть в модели
(`inaccessibleProcessCount`, `compressedAttributionProcessCount`,
`compressedAttributionFailureCount`, `totalProcessCount`); детальный список отказов доступа — это
диагностика конкретного запуска, её место в `diagnose`, а не в семидневной истории.

## Context (from discovery)

- **Проект**: Kotlin/Native, JetBrains Kotlin Toolchain 0.11.1, `macosArm64`, Kotlin 2.4.10,
  `allWarningsAsErrors: true`, `progressiveMode: true` (общие настройки — в
  `harmon.module-template.yaml`). Сборка `./kotlin build`, тесты `./kotlin test`.
- **Модули**: корневой `harmon` (`macos/app`), `nativebridge` (`kmp/lib`, держит
  `cinterop/harmon_native.def`), `selftest` (`macos/app`, проверки, требующие cinterop),
  `plugins/sqldelight-gen` (`jvm/amper-plugin`).
- **Файлы под правку**: `src/dev/yoda/harmon/{history,runtime,analysis,config,cli}/`,
  `sqldelight/dev/yoda/harmon/db/`, `README.md`, `docs/architecture.md`, `docs/history.md` (новый).
- **Тесты**: плоская директория `test/`, `kotlin.test`, общие фикстуры в `test/TestFixtures.kt`.
- **Паттерны**: инъекция через параметры конструктора со значениями по умолчанию
  (`HarmonService`, `UsageCalculator`, `DarwinSystemCollector`); фиксированный путь под
  `~/Library/Application Support/Harmon/` и идиома `currentHomeDirectory()` уже есть в
  `HtmlReportStore`; изоляция сбоя доставки — в `HarmonService.deliverSafely`.
- **Источник решения**: соседний проект `../kotgent` уже прошёл этот путь на том же тулчейне.
  Оттуда скопирован плагин кодогенерации и перенесены задокументированные гоча.

### Скелет, уже стоящий в рабочем дереве

Сделано до плана, чтобы снять два риска до того, как под них закладываться. Сборка и тесты зелёные.

| Файл | Состояние |
| --- | --- |
| `project.yaml`, `module.yaml` | `plugins/sqldelight-gen` зарегистрирован и включён; `$libs.sqldelight.native.driver`; `freeCompilerArgs: [-linker-option, -lsqlite3]` |
| `libs.versions.toml` | sqldelight 2.3.2, sql-psi 0.7.3, одна `version.ref` на 5 координат |
| `plugins/sqldelight-gen/src/Generate.kt` | адаптирован из kotgent: `package dev.yoda.harmon.sqldelight`, `DATABASE_PACKAGE=dev.yoda.harmon.db`, `DATABASE_CLASS_NAME=HarmonDatabase`, `MODULE_NAME=harmon` |
| `plugins/sqldelight-gen/src/SqlDelightEnvironment.kt` | дословная копия из kotgent, `package app.cash.sqldelight.core`, Apache 2.0 © Square |
| `sqldelight/dev/yoda/harmon/db/Samples.sq` | **временная** схема на 5 колонок, заменяется в задаче 2 |
| `test/HistoryDriverSmokeTest.kt` | 3 теста; переписывается под полную схему в задаче 2 |

### Что уже проверено экспериментально

1. **Сгенерированный SQLDelight код компилируется под `allWarningsAsErrors` + `progressiveMode`.**
   Это был главный риск выбранного подхода: чужой генератор, а править его вывод нечем. Сборка
   чистая, ни одного варнинга.
2. **`native-driver` линкуется в тестовый бинарник.** KTC-5573 держит вне тестов только
   *собственный* cinterop-klib (`nativebridge`); `native-driver` несёт свой cinterop в стороннем
   klib, и такие линкуются нормально. Значит слой хранения тестируется настоящей SQLite —
   **никакого разделения на «чистый / нечистый слой» не нужно**, и `selftest` для этой фичи не
   задействуется.
3. **Порядок прагм — `auto_vacuum` замораживается WAL.** На sqlite 3.51:
   `WAL → auto_vacuum=INCREMENTAL → CREATE` даёт `auto_vacuum = 0`, а
   `auto_vacuum=INCREMENTAL → WAL → CREATE` даёт `2`. `foreign_keys` на свежем соединении — `0`.
   Отсюда конструкция задачи 6, см. Technical Details.

## Development Approach

- **testing approach**: TDD — сначала падающий тест на настоящей SQLite, затем реализация. Порядок
  пунктов в чеклистах это отражает.
- complete each task fully before moving to the next
- make small, focused changes
- **CRITICAL: every task MUST include new/updated tests** for code changes in that task
- **CRITICAL: all tests must pass before starting next task** — no exceptions
- **CRITICAL: update this plan file when scope changes during implementation**
- run tests after each change
- maintain backward compatibility

## Testing Strategy

- **unit tests**: обязательны в каждой задаче. Хранилище тестируется на настоящей SQLite через
  **тот же сконфигурированный драйвер, что и продакшн** — голый `inMemoryDriver(Schema)` не годится:
  он не включает `foreign_keys`, и тесты каскада на нём молча ничего не проверяют. Отсюда
  конструкторный шов для драйвера в `HistoryStore` (задача 6).
- **round-trip широких таблиц**: `TestFixtures` для этого непригодны — там
  `activeBytes == inactiveBytes`, `userCpuPercent == cpuPercent` и почти все `*PerSecond` равны
  нулю, так что перестановка двух колонок одного типа пройдёт незамеченной. В задачах 2–4 нужен
  локальный билдер с **уникальным значением-меткой на каждое поле**; `TestFixtures` остаётся для
  сценарных тестов.
- **e2e tests**: UI и e2e-фреймворка в проекте нет. Роль сквозной проверки играет задача 12 —
  прогон живого агента против dev-коллектора с чтением результата через `sqlite3`.
- всё, что видят тесты, должно быть `public`: тестовый сорс-сет не видит `internal` из `src/`.

## Progress Tracking

- mark completed items with `[x]` immediately when done
- add newly discovered tasks with ➕ prefix
- document issues/blockers with ⚠️ prefix
- update plan if implementation deviates from original scope
- keep plan in sync with actual work done

## Solution Overview

Нормализованная схема со справочниками, а не JSON-документ на сэмпл: чтение — ручной SQL, и
`json_extract` по вложенному массиву из 772 элементов работает против самой цели.

Полный SQLDelight с кодогенерацией. На таблице в 25+ колонок сгенерированная функция с
типизированными параметрами исключает перепутанный порядок двух `Double` подряд — ошибку, которую
не поймает ни компилятор, ни тест на арность при ручном биндинге.

Пишет только пользовательский агент, коллектор не трогаем: он рутовый и не должен уметь больше, чем
умеет сейчас.

Ключевые решения и обоснования:

| Решение | Почему |
| --- | --- |
| Гранулярность «всё целиком» — все процессы, система, приложения с бандлом | выбор пользователя; ~222 000 строк/сутки |
| Ротация окном по времени, `historyRetentionDays`, дефолт 7 | ~350 МБ на диске; `0` = историю не вести вообще, файл не создаётся |
| Справочники `process`/`application` | имя и путь перестают повторяться 288 раз в сутки, объём режется примерно вдвое |
| `application_id` в `process_sample`, nullable | `ApplicationUsage.processIds` инвертируется в мапу; «какие хелперы Chrome жрали ночью» — один джойн, и `processIds` не нужно хранить списком |
| `application_sample` **только для групп с бандлом** | `ApplicationGrouper` заводит одиночную группу `process:<pid>:<startedAt>` каждому процессу без бандла (`ApplicationGrouper.kt:56-57`) — на этой машине это сотни групп в сэмпле, дублирующих `process_sample` строка в строку и не несущих ни бита новой информации |
| Метка времени фиксированной ширины | `Instant.toString()` печатает 0/3/6/9 знаков дробной части, и `'…:00.500Z' < '…:00Z'` — лексикографический порядок врёт, а на нём держатся `ORDER BY` и ретеншн |
| Пишет только `run` | у `once`/`diagnose` окно 2 секунды против 300, их числа исказили бы ряд |
| Восстановление `AlertState` с TTL в два интервала | иначе гистерезис `AlertAnalyzer` применит пониженный порог сброса к состоянию суточной давности |
| Явная `toSqlLong()` вместо адаптеров колонок | адаптеры пришлось бы протаскивать через конструктор для ~20 полей и они прячут вопрос знаковости |

Отличие от изначального замысла: одиночные группы приложений не пишутся. Информация не теряется —
такая группа по определению состоит ровно из одного процесса, чья строка уже лежит в
`process_sample`; восстановить её агрегат можно тривиально. Зато `application` перестаёт расти со
скоростью текучки процессов, а `application_sample` — со ~150 000 строк/сутки до ~23 000.

## Technical Details

### Схема

Под ретеншном (каскад от `sample`):

| Таблица | Строк/сутки | Содержимое |
| --- | --- | --- |
| `sample` | 288 | `id INTEGER PRIMARY KEY AUTOINCREMENT`, `captured_at TEXT` (см. ниже), `elapsed_seconds`, вся системная телеметрия `SystemUsage`: swap, power, processor, loadAverages, virtualMemory, storage, `physical_memory_bytes` и четыре счётчика процессов |
| `process` | сотни | справочник, `UNIQUE(pid, started_at)` → `name`, `executable_path`, `uid`, `parent_pid` |
| `process_sample` | ~222 000 | `sample_id`, `process_id`, `application_id` (nullable), все числовые поля `ProcessUsage` |
| `application` | десятки | справочник, `key` (`ApplicationUsage.id`) `UNIQUE` → `name`, `bundle_path NOT NULL` |
| `application_sample` | ~23 000 | `sample_id`, `application_id`, `root_pid`, `process_count`, остальные поля `ApplicationUsage` |
| `alert` | мало | `sample_id`, `key`, `severity`, `title`, `message`, `reported` |
| `alert_delivery` | мало | `sample_id`, `channel`, `successful`, `detail` |

Вне ретеншна — это состояние, а не история: `alert_state(key, settled, failures, retry_at_sample)`
и `agent_state(sample_counter, last_sample_at)`. Колонки `firing` в `alert_state` нет: в снапшот по
определению попадают только горящие ключи, она была бы константой.

`reported = 0` — алерт был над порогом, но вытеснен per-category cap; у такой строки есть только
`key`, потому что `MonitoringReport.suppressedAlertKeys` больше ничего не несёт.

Индексы: `process_sample(sample_id)` и `sample(captured_at)` обязательны — по ним идут выборка
среза, `selectBetween` и удаление по окну. Индекс `(process_id, sample_id)` **не заводим**: он
добавляет ~222 000 записей в сутки ради запроса, который на семидневном окне отработает полным
сканированием за пару секунд.

### Метка времени

`captured_at` — ISO-8601 UTC **фиксированной ширины, усечённый до целых секунд**:
`Instant.fromEpochSeconds(value.epochSeconds).toString()` даёт всегда `YYYY-MM-DDTHH:MM:SSZ`.

Это не косметика. `Clock.System.now().toString()` печатает переменное число знаков дробной части, и
`'2026-07-29T00:05:00.500Z' < '2026-07-29T00:05:00Z'` истинно — более поздний момент сортируется
раньше. На лексикографическом порядке держатся и `ORDER BY captured_at`, и `WHERE captured_at <
cutoff` в ретеншне. Субсекундная точность здесь и не нужна: интервал сэмплирования — сотни секунд.

### Файл и конфигурация драйвера

`~/Library/Application Support/Harmon/history.db`. Каталог создаём сами с режимом `0700` — это
накрывает разом и саму базу, и появляющиеся рядом `history.db-wal` / `history.db-shm`, которые
несут те же данные и пересоздаются при каждом открытии.

Всё настраивается через `NativeSqliteDriver(schema, name, onConfiguration = …)`, а **не** сырыми
прагмами после открытия, потому что драйвер держит раздельные пулы соединений и одноразовая прагма
легла бы только на одно из них:

- `extendedConfig.basePath` — каталог базы;
- `extendedConfig.foreignKeyConstraints = true` — на свежем соединении FK выключены, а на них
  держится каскад ретеншна;
- `extendedConfig.synchronousFlag = NORMAL` — потеря последнего сэмпла при панике приемлема;
- `lifecycleConfig.onCreateConnection` → `PRAGMA auto_vacuum=INCREMENTAL` — **единственный хук,
  выполняющийся до того, как sqliter применит `journal_mode`**. Порядок в
  `NativeDatabaseManager.createConnection`: open → `onCreateConnection` → synchronous → FK →
  `journal_mode` → `migrateIfNeeded(Schema.create)`. Если `auto_vacuum` поставить после WAL, он
  замораживается на `0` навсегда, и ретеншн перестаёт возвращать место (лечится только полным
  `VACUUM`). На уже существующей базе прагма — безвредный no-op.
- `journal_mode=WAL` — уже дефолт sqliter (`JournalMode.WAL`), явная прагма не нужна; тест на `wal`
  всё равно оставляем как регрессионный.

`user_version` **не трогаем**: sqliter сам выставляет его из `Schema.version` в `migrateIfNeeded`, и
расхождение заставило бы драйвер звать пустой `Schema.migrate()` при каждом старте.

### Запись

Одна транзакция на сэмпл: `sample` → справочники (`INSERT … ON CONFLICT DO NOTHING`, затем
`SELECT id`; `last_insert_rowid()` для справочников использовать **нельзя** — при сработавшем
`DO NOTHING` он вернёт id прошлой вставки) → `process_sample` → `application_sample` → алерты →
`alert_state` (`DELETE` + `INSERT`, ключей единицы, диффить незачем) → `agent_state`.

Точка врезки — `HarmonService.handleSample`, внутри существующего `finally`, **после**
`alertState.commit(...)`: в БД должно лечь состояние после сэмпла, а не до.

Деградация: не открылось при старте — сообщение через инжектируемый `logError` и агент работает без
истории до конца процесса; упала запись сэмпла — откат, сообщение, следующий сэмпл пробует снова.
Сообщение о падении записи логируется **один раз до восстановления**, а не каждый сэмпл: sqliter
печатает полный стектрейс до того, как бросить, и на заполненном диске это залило бы лог launchd
стеной красного раз в интервал.

### Ретеншн

`DELETE FROM sample WHERE captured_at < cutoff` с каскадом, затем чистка осиротевших справочников,
затем ограниченный `PRAGMA incremental_vacuum(N)`.

Сирот удалять формой `DELETE FROM process WHERE id NOT IN (SELECT process_id FROM process_sample)`.
Коррелированный `NOT EXISTS` даёт `CORRELATED SCALAR SUBQUERY` со сканированием `process_sample` на
каждую строку справочника, а индекса `process_sample(process_id)` мы сознательно не заводим — на
полутора миллионах строк это превращает часовую уборку в минуты. `NOT IN` даёт один скан плюс
bloom-фильтр.

Периодичность — примерно раз в час: чистая `shouldPrune(sampleIndex, intervalSeconds)`, плюс один
прогон при старте. **Вызывается из `record()`**, иначе ретеншн остаётся мёртвым кодом с зелёными
тестами. `retentionDays` приходит в `HistoryStore` конструктором.

### Типы

SQLDelight знает `INTEGER→Long`, `REAL→Double`, `TEXT→String`, а модель harmon почти вся на `ULong`.
Конверсия явная: `fun ULong.toSqlLong(): Long` с клампом на `Long.MAX_VALUE`, потому что SQLite
INTEGER знаковый и значение выше границы молча стало бы отрицательным. `uid: UInt?` и
`virtualMemoryRegionCount: Int?` клампа не требуют — `?.toLong()` без потерь; клампа требует только
`compressedOrPagedOutBytes: ULong?`, и `?.toSqlLong()` закрывает его без отдельной функции.

Nullable-поля обязаны приезжать обратно как `null`, а не как `0`.

### Миграции

Плагин выбрасывает `.sqm` (`deriveSchemaFromMigrations = false`, `verifyMigrations = false`), и
сгенерированный `Schema.migrate()` остаётся пустым — **добавлять `.sqm` и ждать, что он выполнится,
бесполезно**. Идиома эволюции на будущее: новая таблица — `CREATE TABLE IF NOT EXISTS` в `init`
стора; новая колонка — `ALTER` под проверкой `PRAGMA table_info`, и **не** через `runCatching`
(sqliter печатает стектрейс до того, как бросит, то есть на каждом старте агента).

**Сейчас ничего из этого не реализуем** — у схемы нет ни одной версии в проде. Абзац существует,
чтобы первая же миграция не пошла через `.sqm`; его место — в `docs/history.md`.

## What Goes Where

- **Implementation Steps** (`[ ]`): код, тесты, документация — всё внутри репозитория.
- **Post-Completion** (без чекбоксов): наблюдение за реальным ростом файла и поведением ретеншна,
  которое требует времени, а не действия.

## Implementation Steps

### Task 1: Конверсия ULong в знаковый SQL INTEGER

**Files:**
- Create: `src/dev/yoda/harmon/history/SqlConversions.kt`
- Create: `test/SqlConversionsTest.kt`

- [x] написать падающий тест: `ULong.MAX_VALUE.toSqlLong()` возвращает `Long.MAX_VALUE`, а не `-1`
- [x] написать тест: значения ниже границы конвертируются точно, включая `0uL` и `Long.MAX_VALUE.toULong()`
- [x] написать тест: `null?.toSqlLong()` даёт `null`, а не `0`
- [x] реализовать `public fun ULong.toSqlLong(): Long` с клампом — одна функция, безопасный вызов покрывает nullable
- [x] запустить `./kotlin test` — должны пройти до задачи 2

### Task 2: Метка времени, схема и маппинг таблицы sample

**Files:**
- Create: `sqldelight/dev/yoda/harmon/db/Samples.sq` (замена временной)
- Create: `src/dev/yoda/harmon/history/HistoryRows.kt`
- Create: `test/HistoryTimestampTest.kt`
- Create: `test/HistorySampleRowTest.kt`
- Modify: `test/HistoryDriverSmokeTest.kt`

- [ ] написать падающий тест форматирования: момент без дробной части и момент с дробью внутри той же секунды дают строки, чей лексикографический порядок совпадает с хронологическим
- [ ] написать тест: формат всегда ровно `YYYY-MM-DDTHH:MM:SSZ`, независимо от точности исходного `Instant`
- [ ] написать падающий тест round-trip: каждое поле `SystemUsage` сверяется отдельно, значения строятся локальным билдером с уникальной меткой на поле (не `TestFixtures`)
- [ ] реализовать усечение метки времени до целых секунд
- [ ] заменить временную `Samples.sq` полной схемой `sample` плюс `agent_state`, с `insert`, `lastInsertedId`, `selectBetween`, `deleteOlderThan` и индексом `sample(captured_at)`
- [ ] реализовать в `HistoryRows` маппинг `SystemUsage` → параметры сгенерированного `insert`
- [ ] переписать `HistoryDriverSmokeTest` под полную схему, сохранив его смысл — доказательство линковки стороннего klib в тестовый бинарник
- [ ] запустить `./kotlin test` — должны пройти до задачи 3

### Task 3: Схема и маппинг процессов

**Files:**
- Create: `sqldelight/dev/yoda/harmon/db/Processes.sq`
- Modify: `src/dev/yoda/harmon/history/HistoryRows.kt`
- Create: `test/HistoryProcessRowTest.kt`

- [ ] написать падающий тест round-trip всех числовых полей `ProcessUsage` с уникальной меткой на поле
- [ ] написать тест: повторный сэмпл того же процесса не плодит вторую строку в справочнике, а два процесса с одним pid и разным `started_at` дают две
- [ ] написать тест: `compressedOrPagedOutBytes = null` и `virtualMemoryRegionCount = null` приезжают обратно как `null`
- [ ] написать тест: процесс из группы с бандлом получает `application_id`, процесс из одиночной группы без бандла — `null`
- [ ] создать `Processes.sq`: справочник `process` с `UNIQUE(pid, started_at)`, `process_sample` с FK на `sample` и `application`, индекс на `sample_id`
- [ ] реализовать маппинг и инверсию `processIds` в мапу `pid → application_id`, пропускающую группы без `bundlePath`
- [ ] запустить `./kotlin test` — должны пройти до задачи 4

### Task 4: Схема и маппинг приложений

**Files:**
- Create: `sqldelight/dev/yoda/harmon/db/Applications.sq`
- Modify: `src/dev/yoda/harmon/history/HistoryRows.kt`
- Create: `test/HistoryApplicationRowTest.kt`

- [ ] написать падающий тест round-trip всех полей `ApplicationUsage` с уникальной меткой на поле
- [ ] написать тест: группа без `bundlePath` не порождает ни строки справочника, ни `application_sample`
- [ ] написать тест: справочник дедуплицирует по `key` между сэмплами
- [ ] создать `Applications.sq`: справочник `application` с `UNIQUE(key)` и `bundle_path NOT NULL`, `application_sample`
- [ ] реализовать маппинг с отбором групп по наличию бандла
- [ ] запустить `./kotlin test` — должны пройти до задачи 5

### Task 5: Схема журнала алертов и состояния

**Files:**
- Create: `sqldelight/dev/yoda/harmon/db/Alerts.sq`
- Modify: `src/dev/yoda/harmon/history/HistoryRows.kt`
- Create: `test/HistoryAlertRowTest.kt`

- [ ] написать падающий тест: репортованный алерт сохраняется целиком, `severity` восстанавливается в тот же `Severity`
- [ ] написать тест: ключ из `suppressedAlertKeys` пишется с `reported = 0` и без текста, и отличим от репортованного
- [ ] написать тест: `alert_delivery` хранит результат по каждому каналу, включая упавший, с его `detail`
- [ ] создать `Alerts.sq`: `alert`, `alert_delivery`, `alert_state` без колонки `firing`
- [ ] реализовать маппинг алертов, подавленных ключей и результатов доставки
- [ ] запустить `./kotlin test` — должны пройти до задачи 6

### Task 6: Открытие базы: конфигурация драйвера, каталог, права

**Files:**
- Create: `src/dev/yoda/harmon/history/HistoryStore.kt`
- Create: `test/HistoryStoreOpenTest.kt`

- [ ] написать падающий тест: на **свежем** файле во временном каталоге `PRAGMA auto_vacuum` отвечает `2` — то есть прагма успела до `journal_mode`
- [ ] написать тест: `PRAGMA journal_mode` отвечает `wal`
- [ ] написать тест: каталог создан с правами `0700`
- [ ] написать тест: FK включены и удаление строки `sample` каскадом уносит зависимые строки
- [ ] написать тест: база в недоступном каталоге → `openOrNull` вернул `null`, сообщение попало в инжектированный логгер, исключение наружу не вышло
- [ ] реализовать `HistoryStore` с конструкторными `homeDirectory` (по образцу `HtmlReportStore`), `logError: (String) -> Unit = ::printError` и швом для драйвера, чтобы тесты получали ту же конфигурацию
- [ ] реализовать конфигурацию через `onConfiguration`: `basePath`, `foreignKeyConstraints`, `synchronousFlag`, `auto_vacuum` в `onCreateConnection`; создание каталога `0700`; `user_version` не трогать
- [ ] запустить `./kotlin test` — должны пройти до задачи 7

### Task 7: Запись сэмпла одной транзакцией

**Files:**
- Modify: `src/dev/yoda/harmon/history/HistoryStore.kt`
- Create: `test/HistoryStoreRecordTest.kt`

- [ ] написать падающий тест: один `MonitoringReport` записывается целиком — сэмпл, все процессы, приложения с бандлом, алерты
- [ ] написать тест отката: сорвать запись предсказуемо — `driver.execute(null, "DROP TABLE application_sample", 0)` перед `record` — и убедиться, что в базе не осталось ни строки этого сэмпла
- [ ] написать тест: два последовательных сэмпла переиспользуют строки справочников, а не дублируют их
- [ ] реализовать `record(...)` одной транзакцией в порядке из Technical Details, с `SELECT id` для справочников вместо `last_insert_rowid()`
- [ ] запустить `./kotlin test` — должны пройти до задачи 8

### Task 8: Ретеншн и его подключение к пути записи

**Files:**
- Create: `src/dev/yoda/harmon/history/Retention.kt`
- Modify: `src/dev/yoda/harmon/history/HistoryStore.kt`
- Create: `test/RetentionTest.kt`

- [ ] написать падающий тест `shouldPrune(sampleIndex, intervalSeconds)`: срабатывает примерно раз в час при разных интервалах, включая интервал больше часа
- [ ] написать тест `retentionCutoff(now, days)`: граница считается от `now`, ровно на границе строка остаётся
- [ ] написать тест: удаление сэмпла каскадом уносит его `process_sample`, `application_sample`, `alert`, `alert_delivery`
- [ ] написать тест: осиротевшие строки `process` и `application` вычищаются, а используемые остаются
- [ ] написать тест: `alert_state` и `agent_state` ретеншн не трогает
- [ ] написать тест: ретеншн **вызывается из `record`** — после достаточного числа сэмплов старые исчезают сами, без явного вызова из теста
- [ ] реализовать чистые функции, удаление с каскадом, чистку сирот формой `NOT IN`, ограниченный `PRAGMA incremental_vacuum(N)` и конструкторный `retentionDays` у стора
- [ ] запустить `./kotlin test` — должны пройти до задачи 9

### Task 9: Снапшот, восстановление и TTL AlertState

**Files:**
- Modify: `src/dev/yoda/harmon/analysis/AlertState.kt`
- Modify: `test/AlertStateTest.kt`
- Modify: `src/dev/yoda/harmon/history/HistoryStore.kt`
- Create: `test/AlertStateSnapshotTest.kt`

- [ ] написать падающий тест: `snapshot()` после `commit` содержит горящие ключи с их флагами и счётчиками плюс текущий `sampleCounter`, а погасшие ключи в него не попадают
- [ ] написать тест: `AlertState(restored = snapshot())` ведёт себя как исходный — тот же `newlyActive`, тот же backoff
- [ ] написать тест на сохранённый backoff: ключ, отложенный на N сэмплов до рестарта, после восстановления становится пушабельным ровно через N сэмплов — ни раньше, ни позже
- [ ] написать тест `isSnapshotFresh`: разрыв в один и два интервала свежий, в три — нет, уход часов назад — нет
- [ ] написать тест: снапшот переживает запись в базу и чтение обратно без потерь, включая `sampleCounter`
- [ ] реализовать `AlertKeyState`, `AlertStateSnapshot` (несёт `sampleCounter`), `AlertState.snapshot()`, конструкторный `restored`, засевающий поле `sample`, и `isSnapshotFresh`
- [ ] реализовать в сторе сохранение и чтение снапшота вместе с `agent_state`
- [ ] запустить `./kotlin test` — должны пройти до задачи 10

> `retryAtSample` — **абсолютный** номер сэмпла (`AlertState.kt`, `retriesAfter` пишет `sample + it`).
> Если восстановить ключи, но не счётчик, `sample` начнётся с нуля против `retryAtSample` в тысячах,
> и `newlyActive` отфильтрует такой ключ практически навсегда — алерт с накопленным backoff
> перестанет пушиться совсем. Отсюда `sampleCounter` в снапшоте и отдельный тест на него.

### Task 10: Врезка записи в цикл агента

**Files:**
- Modify: `src/dev/yoda/harmon/runtime/HarmonService.kt`
- Create: `test/HarmonServiceHistoryTest.kt`

- [ ] написать падающий тест: после `handleSample` в базе есть сэмпл, и состояние алертов в нём — послекоммитное
- [ ] написать тест: результат доставки по каждому каналу, включая упавший, доехал до `alert_delivery`
- [ ] написать тест: исключение из стора не роняет цикл и не мешает `alertState.commit` — следующий сэмпл обрабатывается нормально
- [ ] написать тест: повторный сбой записи логируется один раз, а не каждый сэмпл
- [ ] написать тест: с `history = null` цикл работает как раньше, ничего не пишется
- [ ] написать тест: `alertState` при старте восстанавливается из свежего снапшота и **не** восстанавливается из протухшего
- [ ] протянуть `results: List<DeliveryResult>` через `DeliveryOutcome` из `deliverSample`
- [ ] добавить параметр `history` последним, с дефолтом `null`; построить `alertState` в инициализаторе свойства
- [ ] реализовать `recordSafely` по образцу `deliverSafely` и вызвать его в `finally` после `commit`
- [ ] запустить `./kotlin test` — должны пройти до задачи 11

> `DeliveryOutcome` — `private` вложенный класс, а `handleSample` возвращает `Unit`; тестовый
> сорс-сет не видит `private`. Поэтому доставка проверяется **через строки `alert_delivery`**, а не
> через возвращаемое значение — публиковать внутренний тип только ради теста не нужно.

### Task 11: Конфиг и проводка в CLI

**Files:**
- Modify: `src/dev/yoda/harmon/config/Config.kt`
- Modify: `src/dev/yoda/harmon/cli/Cli.kt`
- Modify: `test/ConfigLoaderTest.kt`
- Modify: `config/harmon.conf.example`

- [ ] написать падающий тест: `historyRetentionDays` парсится, дефолт `7`, `0` даёт `null`
- [ ] написать тест: отрицательное и нечисловое значение отвергаются с внятным сообщением
- [ ] написать тест: ключ присутствует в `redactedDescription()`
- [ ] написать тест: при `historyRetentionDays = 0` стор не открывается и файл базы не создаётся
- [ ] добавить поле в `HarmonConfig`, ключ в `knownKeys`, разбор через существующий `optionalPositiveLong`
- [ ] в `Cli.kt` открыть стор явно только для `Command.Run` и только при ненулевом ретеншне; `once` и `diagnose` оставить на дефолтном `null`
- [ ] дописать ключ с комментарием в `config/harmon.conf.example`
- [ ] запустить `./kotlin test` — должны пройти до задачи 12

> Сама проводка в `Cli.kt` юнит-тестом не покрывается: `Command.Run` уходит в `runForever()`.
> Она проверяется в задачах 12 и 13.

### Task 12: Ручная проверка на живой машине

**Files:**
- Modify: `docs/history.md`

- [ ] собрать debug-бинарник и поднять dev-коллектор на `/tmp/harmon-dev.sock` по рецепту из `CLAUDE.md`
- [ ] запустить агента с коротким `intervalSeconds` и дать ему набрать несколько сэмплов
- [ ] проверить через `sqlite3`: число строк в `process_sample` соответствует числу процессов, `application_sample` содержит только группы с бандлом, справочники не дублируются, `journal_mode` = `wal`, `auto_vacuum` = `2`
- [ ] убить и перезапустить агента с горящим алертом; убедиться, что уведомление не ушло повторно, а `alert_state` восстановился вместе со счётчиком
- [ ] проверить ретеншн: подменить `captured_at` части строк на старые, дождаться прогона, убедиться что каскад отработал, сироты вычищены, и после `PRAGMA wal_checkpoint(TRUNCATE)` файл действительно уменьшился
- [ ] записать этот рецепт в `docs/history.md`

### Task 13: Verify acceptance criteria

- [ ] verify all requirements from Overview are implemented
- [ ] verify edge cases are handled: пустой список процессов, отсутствующая батарея, `storage.available = false`, сэмпл без алертов, все процессы без бандла
- [ ] run full test suite: `./kotlin test`
- [ ] run release build: `./kotlin build --variant release`
- [ ] убедиться, что `once`, `diagnose`, `check-config` и `test-notifications` по-прежнему не создают файл базы

### Task 14: [Final] Update documentation

- [ ] написать `docs/history.md`: DDL, смысл колонок, примеры запросов (включая `datetime(captured_at,'localtime')`), политику миграций, оговорку про замороженные первые значения в справочнике `process`, и рецепт ручной проверки из задачи 12
- [ ] дописать в `README.md` секцию про историю, её размер (~350 МБ при семи днях) и ключ `historyRetentionDays`
- [ ] дописать в `docs/architecture.md`: база на стороне агента, не коллектора; каталог `0700`; WAL; почему одиночные группы приложений не пишутся
- [ ] дописать в `CLAUDE.md` строку про новую многомодульную структуру с `plugins/sqldelight-gen` и про то, что `.sqm` не работают
- [ ] переместить этот план в `docs/plans/completed/`

> CLAUDE.md требует, чтобы изменение документации ехало в том же коммите, что и изменение
> поведения. Если план ложится одним коммитом — это выполнено автоматически; если задачи коммитятся
> по отдельности, правки `README.md` и `docs/architecture.md` едут с задачами 10–11, а не сюда.

## Post-Completion

*Требует времени и наблюдения, а не действия — без чекбоксов.*

**Наблюдение за реальным поведением:**

- фактический рост файла за первую неделю против оценки ~350 МБ; если сильно расходится — кандидат
  на пересмотр `historyRetentionDays` по умолчанию либо на отказ от записи полностью простаивающих
  процессов;
- сколько времени реально занимает запись ~800 строк в транзакции на загруженной машине; если
  заметно на фоне интервала — кандидат на вынос записи из цикла;
- поведение `incremental_vacuum` на длинной дистанции: возвращается ли место так, как ожидается, и
  не растёт ли `-wal` между чекпойнтами;
- насколько быстро растёт справочник `process` при реальной текучке и не станет ли часовая чистка
  сирот заметной.

**Возможное продолжение, намеренно вынесенное за скоуп:**

- CLI-команды чтения (`harmon history --at`, топ за интервал) — сознательно не делаем, чтение через
  `sqlite3`; если запросы окажутся частыми и однотипными, это первый кандидат на возврат в скоуп;
- часовые агрегаты поверх сырых сэмплов для хранения дольше окна ретеншна;
- построчное хранение `processIssues`, если диагностика отказов доступа понадобится исторически.
