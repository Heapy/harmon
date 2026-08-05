# Orphan detect: замечать процессы, потерявшие родителя

## Overview

`harmon run` начинает замечать момент, когда живой процесс теряет родителя, поднимать
по этому поводу алерт и сохранять факт в историю.

Проблема, которую это решает: брошенный процесс ничем себя не проявляет. Он не грузит
CPU, не растёт по памяти, не падает — он просто продолжает занимать память и порты
неделями. Ни один существующий порог harmon его не ловит, потому что он не «слишком
много чего-то потребляет»; он просто не должен существовать. Найти такие процессы
вручную дорого и ненадёжно: `ps` показывает `ppid=1` у 653 процессов из 957 на этой
машине, и отличить осиротевшего от штатного демона по снимку нельзя — попытка сделать
это через `launchctl list` уже дала ложный вывод (`kotomka` оказался launchd-агентом
`dev.kotomka` с `KeepAlive`).

Ключ к решению: **переход `ppid → 1` — единственный настоящий сигнал**, и harmon
находится в уникальной позиции, чтобы его увидеть, потому что он единственный ведёт
непрерывную запись. `ps` видит снимок; переход виден только тому, кто сравнивает два
последовательных чтения.

Интеграция с существующей системой минимальна и это осознанный выбор: `parentPid` уже
течёт через IPC в `RawProcessSample`, `UsageCalculator` уже держит предыдущий сэмпл
процесса в своём цикле, а `AlertState` уже устроен как edge detection. Работа сводится
к одному сравнению, одному полю, одной колонке и одному правилу алерта.

## Context (from discovery)

**Файлы и компоненты:**

- `core/src/dev/yoda/harmon/model/Models.kt:21` — `RawProcessSample.parentPid`, уже есть, `@Serializable`
- `core/src/dev/yoda/harmon/model/Models.kt:173` — `ProcessUsage`, **не** `@Serializable`, живёт только в агенте
- `core/src/dev/yoda/harmon/model/Models.kt:307` — `Alert(key, severity, title, message)`
- `core/src/dev/yoda/harmon/monitor/UsageCalculator.kt:33-37` — уже строит `previousByIdentity` и держит `previousProcess` в цикле
- `core/src/dev/yoda/harmon/analysis/AlertAnalyzer.kt:49` — `alertsFor`, где живут все правила
- `core/src/dev/yoda/harmon/analysis/AlertAnalyzer.kt:269-290` — `selectAlerting`, типизирован под `List<ApplicationUsage>` и ранжирует по числовой метрике
- `core/src/dev/yoda/harmon/analysis/AlertState.kt:143-147` — `unsettled` / `newlyActive` фильтруют **список `Alert`**, не ключи
- `core/src/dev/yoda/harmon/analysis/AlertState.kt:161-173` — `commit`; `retries` выживают только для ключей, оставшихся в `firingKeys`
- `core/src/dev/yoda/harmon/runtime/HarmonService.kt:199-200` — `createSample`: `calculate` → `analyze` → `recordSafely`
- `core/src/dev/yoda/harmon/runtime/HarmonService.kt:325-343` — `pushPlan`; пуш строится из `report.alerts`
- `core/src/dev/yoda/harmon/config/Config.kt:43-51` — пороги, каждое правило под nullable-ключом
- `core/src/dev/yoda/harmon/config/Config.kt:67` — `intervalSeconds = 300`, `:70` — `maxAlertsPerCategory = 3`
- `core/src/dev/yoda/harmon/report/ReportFormatter.kt:105-116` — рендер `report.alerts`, общий для всех правил
- `history-sqlite/sqldelight/dev/yoda/harmon/db/Processes.sq:87-91` — `insertProcess`, upsert только `executable_path` и только из null
- `history-sqlite/src/.../HistoryRows.kt:98` — `ProcessesQueries.upsertProcess(process)`
- `history-sqlite/src/.../HistoryStore.kt:63-70` — конструктор; `init`-блока сегодня **нет**, его добавляет миграция
- `history-sqlite/src/.../HistoryStore.kt:136-143` — цикл `record`, где id процесса вычисляется инлайн
- `history-sqlite/test/TestFixtures.kt:359` — `withScratchHome`, готовый хелпер
- `docs/history.md:322` — `parent_pid` замерзает при первом обнаружении
- `docs/history.md:549-572` — раздел «Changing the schema»

**Найденные паттерны:**

- DI через параметры конструктора; тяжёлые реализации никогда не в дефолтах
- Тесты хранилища открывают store через `openOrNull` над scratch `HOME`, чтобы работала
  производственная конфигурация драйвера (`inMemoryDriver` врёт про foreign keys)
- `settings.kotlin` из `harmon.module-template.yaml` включает `allWarningsAsErrors`
- Тесты в каждом модуле лежат плоско и не объявляют package
- **Весь KDoc в репозитории на английском** — план на русском, комментарии в коде нет
- Фикстуры дублируются между модулями: `processUsage` есть и в `core/test/TestFixtures.kt:147`,
  и в `history-sqlite/test/TestFixtures.kt:162`

**Зависимости:**

- Изменения замкнуты в `core` и `history-sqlite`
- `bridge-probe`, IPC-протокол и `harmon-collector` **не трогаются**: `parentPid` уже
  доставляется, привилегированная граница остаётся как есть
- Нативный слой не участвует: ни C-харнесс, ни `selftest`, ни `docs/native-testing.md`

## Development Approach

- **testing approach**: Regular (сначала код, потом тесты). Причина не стилистическая:
  в Kotlin/Native тест на ещё не существующее поле `ProcessUsage` или на ещё не
  сгенерированный SQLDelight-запрос не компилируется, так что «красная фаза» TDD была бы
  ошибкой компиляции, а не падением ассерта.
- завершать каждую задачу полностью перед переходом к следующей
- вносить небольшие сфокусированные изменения
- **CRITICAL: каждая задача ОБЯЗАНА включать новые/обновлённые тесты** для своих изменений
  - тесты не опциональны — это обязательная часть чеклиста
  - unit-тесты для новых функций/методов
  - unit-тесты для изменённых функций/методов
  - новые кейсы для новых веток кода
  - обновление существующих кейсов, если поведение изменилось
  - тесты покрывают и успешные, и ошибочные сценарии
  - **исключение задокументировано явно**: задачи 1 и 4 не несут проверяемого поведения
    сами по себе (объявление поля и определение SQL-запроса); в них указано, какая
    задача их покрывает
- **CRITICAL: все тесты должны проходить до начала следующей задачи** — без исключений
- **CRITICAL: обновлять этот файл плана при изменении объёма работ**
- прогонять тесты после каждого изменения
- сохранять обратную совместимость (существующие базы открываются и работают)
- **весь план укладывается в один коммит** — иначе обязательство CLAUDE.md «правка
  документации в том же коммите» не выполняется финальной задачей документации

**Порядок сборки** (требование `CLAUDE.md`, не привычка):

```shell
./kotlin build
./kotlin test
```

`./kotlin test` не линкует `selftest`, поэтому `SelftestBridgeTest` упадёт с
инструкцией, если пропустить `build`.

## Testing Strategy

- **unit-тесты**: обязательны в каждой задаче (см. Development Approach)
- **e2e-тесты**: UI-based e2e в проекте нет. Их роль выполняет ручная проверка на живой
  машине через локальный непривилегированный коллектор — вынесена в Post-Completion,
  потому что требует внешнего действия (запуск коллектора, убийство процесса-родителя)
- **интеграционные тесты хранилища**: против настоящего SQLite через `openOrNull` над
  scratch `HOME`. `inMemoryDriver(Schema)` здесь непригоден — он не включает foreign keys
  и имеет одно соединение, то есть скрывает ровно те два дефекта, которые тест ищет

## Progress Tracking

- отмечать выполненное `[x]` сразу по завершении
- новые обнаруженные задачи добавлять с префиксом ➕
- проблемы и блокеры документировать с префиксом ⚠️
- обновлять план, если реализация отклоняется от исходного объёма
- держать план в синхроне с фактически сделанной работой

## Solution Overview

**Архитектура.** Детект — одно сравнение внутри уже существующего цикла
`UsageCalculator`, где `previousProcess` уже под рукой. Результат кладётся в новое поле
`ProcessUsage`, у которого два независимых потребителя: `AlertAnalyzer` поднимает алерт,
`HistoryStore` ставит отметку в БД. `UsageCalculator` при этом остаётся чистой функцией
двух снимков — он не знает ни про историю, ни про алерты.

**Ключевые решения и обоснование:**

1. **Сигнал — зафиксированный переход, а не значение `ppid`.** Из снимка сиротство
   неотличимо от штатной демонизации. Из пары снимков — отличимо однозначно.

2. **Штатная демонизация отфильтровывается интервалом бесплатно.** Double fork
   (`tmux -L`, `ssh -f`, `gpg-agent --daemon`, `cloudflared`) укладывается в миллисекунды
   между сэмплами: такой процесс harmon впервые видит уже с `ppid=1`, и перехода не
   возникает. Чтобы переход попал в запись, процесс должен прожить с настоящим родителем
   хотя бы один сэмпл — при `intervalSeconds = 300` это пять минут. Пятиминутный процесс,
   чей родитель затем умер, — авария, а не демонизация. Ни `launchctl`, ни эвристик по
   возрасту не требуется.

3. **`identity = (pid, startedAt)` исключает путаницу с переиспользованием pid**
   конструктивно: другой процесс с тем же pid имеет другой `startedAt` и просто не
   находится в `previousByIdentity`.

4. **Виновник уже хранится.** `process.parent_pid` замерзает при первом обнаружении
   (`docs/history.md:322`) — то, что выглядело помехой, здесь работает на нас: колонка
   консервирует настоящего родителя, а переход правит только новую колонку.

5. **Имя родителя резолвится из `previous` снимка**, где он ещё жив. Это и превращает
   алерт из «процесс осиротел» в «найди источник».

**Принятые ограничения** — четыре: три следствия одного и того же (событие видно ровно
в одном сэмпле) и одно, вылезшее из того, что фильтр демонизации держится на длине
интервала.

- **Рестарт агента — слепое пятно.** Переходы, случившиеся пока harmon не работал,
  не видны никак. Альтернатива (эвристика по снимку) уже доказала ненадёжность.
- **Одноразовая доставка.** Алерт живёт один сэмпл, поэтому механизм ретрая
  (`AlertState.retryAtSample`) на него не распространяется: `pushPlan` строит доставку из
  `report.alerts` (`HarmonService.kt:333`), а `commit` выбрасывает `retries` для ключей,
  выпавших из `firingKeys` (`AlertState.kt:172`). Удержать ключ мало — нужен сам объект
  `Alert`, а пересобрать его на следующем сэмпле нечем: имени мёртвого родителя в данных
  уже нет. Если push упал, уведомление потеряно. Факт при этом **не** потерян: он лежит в
  `reparented_at`, в таблице `alert` (`HistoryStore.kt:145`) и в тексте отчёта.
- **Подавленный каскад теряется навсегда, а не откладывается.** `AlertAnalyzer.kt:43-45`
  оставляет подавленный ключ в `firingKeys` только если он **уже** был активен; новый
  ключ, впервые выбитый за `maxAlertsPerCategory`, не попадает в состояние и второго шанса
  не получает. При падении супервизора с 50 детьми уведомление уйдёт про троих. Остальные
  47 попадут в `suppressedAlertKeys` отчёта и в `reparented_at`, но не в push.

- **`once` и `diagnose` — ложные срабатывания без фильтра.** Аргумент про демонизацию
  держится на `intervalSeconds = 300`; у разовых команд окно `SAMPLE_SECONDS_RANGE = 1..300`
  (`Config.kt:43`), и двухсекундный интервал регулярно попадает внутрь double fork.
  Правило намеренно **не** гейтится на долгоживущий путь: оно там полезно ровно так же,
  а гейт означал бы, что `diagnose` не показывает того, что покажет агент. Цена не только
  «странная строка в разовом отчёте»: `once --notify` доставляет (`Cli.kt:45-50`), так что
  ложная сирота уходит в webhook, Telegram и Notification Center. Выход — `orphanAlerts=false`.
  В истории при этом ничего не остаётся: ни `once`, ни `diagnose` её не пишут.

Первые три — цена того, что событие точечно; она принимается сознательно, альтернатива
потребовала бы состояния, которого в `AlertAnalyzer` нет по устройству. Четвёртое — цена
того, что фильтр демонизации бесплатный: он ничего не знает про процессы, только про длину
окна.

**Вынесено за границу:** зомби-детект (`pbi_status == SZOMB`). Другая болезнь — родитель
жив, но не зовёт `wait()` — и другая цена: новое поле в `bridge-probe`, в IPC-протоколе,
C-чек в харнессе и правка списка принятых пробелов в `docs/native-testing.md`.

## Technical Details

**Новое поле в `ProcessUsage`:**

```kotlin
/**
 * Who the parent was in the previous sample, when it changed in this one; null otherwise.
 * The name is for the alert message and is resolved from the previous snapshot, where the
 * parent was still alive.
 */
val reparentedFrom: ReparentedFrom?

data class ReparentedFrom(val pid: Int, val name: String?)
```

Поле — «сменил родителя», а не «осиротел». Проверку `parentPid == 1` применяет
потребитель; на Darwin другого перехода не бывает (нет аналога linux-овского
`PR_SET_CHILD_SUBREAPER`), но проверка остаётся явной, а не подразумеваемой.

**Детект в `UsageCalculator`:**

```kotlin
val previousProcess = previousByIdentity[currentProcess.identity]
// ...
reparentedFrom = previousProcess
    ?.takeIf { it.parentPid != currentProcess.parentPid }
    ?.let { ReparentedFrom(it.parentPid, nameByPid[it.parentPid]) }
```

`nameByPid` строится из `previous.processes` один раз на вызов.

**Условие у обоих потребителей — одно и то же:**

```kotlin
process.reparentedFrom != null && process.parentPid == 1
```

**Колонка:**

```sql
ALTER TABLE process ADD COLUMN reparented_at TEXT;   -- ISO-8601, как sample.captured_at
```

Тип — текстовое время, а не `REFERENCES sample(id)`: ретеншен удаляет старые сэмплы,
`ON DELETE CASCADE` снёс бы сам процесс, `SET NULL` потерял бы факт. Строка времени
переживает обрезку окна.

**Запись — отдельный `UPDATE`, а не расширение `insertProcess`:**

```sql
markReparented:
UPDATE process SET reparented_at = ?
WHERE id = ? AND reparented_at IS NULL;
```

Существующий upsert (`Processes.sq:87-91`) намеренно узкий — он дописывает только
`executable_path` и только из null. Ставить туда `reparented_at` значило бы трогать его
на каждом сэмпле каждого процесса. Новый запрос выполняется только когда сработало
условие выше — на здоровой машине ноль раз за сэмпл. `reparented_at IS NULL` делает
запись идемпотентной.

**Миграция** — первая в проекте. Форма задана `docs/history.md:562-569`:
`ALTER TABLE … ADD COLUMN` под чтением `PRAGMA table_info(process)`, **не** под
`runCatching`: sqliter печатает полный стектрейс до того, как бросит, поэтому
проглоченное исключение всё равно даёт стену красного в launchd-логе на каждом старте.

Порядок колонок значения не имеет: SQLDelight разворачивает `SELECT *` в явный список
имён на генерации (`ProcessesQueries.kt:29`), так что дописанная в конец колонка читается
по имени.

**Алерт:**

- ключ `orphan:process:<pid>:<startedAt>` — сегмент `process:` повторяет форму
  существующих ключей вида `cpu:process:200:666` (`docs/history.md:543`), которые берут её
  из id одиночной группы (`ApplicationGrouper.kt:56-57`). Слово `orphan` в ключе остаётся,
  хотя колонка называется `reparented_at`: ключ обращён к пользователю и говорит на языке
  POSIX, колонка живёт в схеме, где `orphan` уже занято под «строку без ссылающихся детей»
- severity всегда `WARNING`: у события нет градации, а `CRITICAL` у существующих правил
  берётся из «порог × 2», чего здесь нет
- сообщение вида `node (pid 44559) lost its parent codex (pid 44268)`; без имени
  родителя — `node (pid 44559) lost its parent (pid 44268)`
- **cap применяется инлайн, а не через `selectAlerting`.** Существующий хелпер
  (`AlertAnalyzer.kt:269-290`) типизирован под `List<ApplicationUsage>` и ранжирует по
  числовой метрике `value`; у сиротства метрики нет. Обобщать хелпер ради одного
  вызова — лишняя связанность, поэтому правило берёт `take(maxAlertsPerCategory)` само,
  **отсортировав по pid**, а остаток кладёт в `suppressed`. Сортировка нужна не для
  красоты: без неё тест «5 сирот при cap 3» недетерминирован в том, какие два подавлены
- **гейта в конфиге нет по умолчанию** — см. задачу 7

**Поток данных:**

```
harmon-collector → RawSystemSnapshot (parentPid уже внутри)
    → UsageCalculator.calculate(previous, current)   ← детект здесь
        → ProcessUsage.reparentedFrom
            ├→ AlertAnalyzer  → Alert(orphan:process:…)   → ReportFormatter (общий рендер)
            └→ HistoryStore   → UPDATE process SET reparented_at
```

## What Goes Where

- **Implementation Steps** (`[ ]`): изменения кода, тесты, документация — всё достижимо
  внутри репозитория
- **Post-Completion** (без чекбоксов): проверка на живой машине через локальный коллектор
  и проверка миграции на существующей 100-мегабайтной базе — требуют внешних действий

## Implementation Steps

### Task 1: Добавить поле reparentedFrom в модель

**Files:**
- Modify: `core/src/dev/yoda/harmon/model/Models.kt`
- Modify: `core/test/TestFixtures.kt`
- Modify: `history-sqlite/test/TestFixtures.kt`

- [x] добавить `data class ReparentedFrom(val pid: Int, val name: String?)` рядом с `ProcessUsage`
- [x] добавить поле `val reparentedFrom: ReparentedFrom?` в `ProcessUsage` со значением по умолчанию `null`
- [x] добавить параметр `reparentedFrom` в обе фикстуры `processUsage` — `core/test/TestFixtures.kt:147` и `history-sqlite/test/TestFixtures.kt:162` (дублируются намеренно, тестовые исходники не экспортируются между модулями)
- [x] написать KDoc **на английском**: семантика «сменил родителя», а не «осиротел», и почему имя приходит из предыдущего снимка
- [x] проверить, что `ProcessUsage` не `@Serializable` и поле не попадает в IPC-протокол
- [x] тестов нет: задача объявляет поле и не несёт поведения. Поведение покрывает задача 2
- [x] `./kotlin build` — компилируется до перехода к задаче 2

### Task 2: Детект перехода в UsageCalculator

**Files:**
- Modify: `core/src/dev/yoda/harmon/monitor/UsageCalculator.kt`
- Modify: `core/test/UsageCalculatorTest.kt`

- [x] построить `nameByPid` из `previous.processes` один раз на вызов `calculate`
- [x] в цикле по процессам заполнить `reparentedFrom` сравнением `previousProcess.parentPid != currentProcess.parentPid`
- [x] прокинуть значение в `calculateProcessUsage`, сохранив её чистоту (никаких обращений к истории или конфигу)
- [x] написать тест: переход при совпадающем `identity` даёт `reparentedFrom` с прежним pid родителя
- [x] написать тест: процесса не было в `previous` → `null` (это штатная демонизация, которую фильтрует интервал)
- [x] написать тест: тот же `pid`, другой `startedAt` → `null` — переиспользование pid не путается с переходом
- [x] написать тест: имя родителя резолвится из `previous`; если родителя там не было → `name == null`, но `pid` заполнен
- [x] написать тест: переход к родителю, отличному от 1, заполняет `reparentedFrom` — фильтрация это забота потребителя, а не калькулятора
- [x] `./kotlin build && ./kotlin test` — должны пройти до задачи 3

### Task 3: Правило алерта в AlertAnalyzer

**Files:**
- Modify: `core/src/dev/yoda/harmon/analysis/AlertAnalyzer.kt`
- Modify: `core/test/AlertAnalyzerTest.kt`
- Modify: `core/test/ReportFormatterTest.kt`

- [x] добавить в `alertsFor` правило по `usage.processes` с условием `reparentedFrom != null && parentPid == 1`
- [x] сформировать ключ `orphan:process:<pid>:<startedAt>` и сообщение с именем и pid умершего родителя
- [x] выставить `Severity.WARNING` безусловно, KDoc **на английском** объясняет, почему `CRITICAL` здесь неприменим
- [x] реализовать cap инлайн: отсортировать по pid, `take(maxAlertsPerCategory)`, остаток в `suppressed`; **не** пытаться переиспользовать `selectAlerting` — он типизирован под `ApplicationUsage` и требует числовой метрики
- [x] KDoc отмечает, что подавленный orphan теряется навсегда: `firingKeys` держит подавленный ключ только если тот уже был активен, а edge второй раз не наступит
- [x] написать тест: один осиротевший процесс даёт один алерт с ожидаемым ключом и текстом
- [x] написать тест: переход к родителю, отличному от 1, алерта **не** даёт
- [x] написать тест: родитель без имени даёт сообщение с голым pid
- [x] написать тест: 5 сирот при `maxAlertsPerCategory = 3` дают 3 алерта по наименьшим pid и 2 подавленных ключа
- [x] написать тест: процесс без перехода алерта не даёт
- [x] написать тест в `ReportFormatterTest`: текстовый отчёт проносит orphan-алерт через общий блок `alerts` (`ReportFormatter.kt:105-116`) — своего кода рендера не требуется
- [x] `./kotlin build && ./kotlin test` — должны пройти до задачи 4

### Task 4: Колонка reparented_at и запрос markReparented

**Files:**
- Modify: `history-sqlite/sqldelight/dev/yoda/harmon/db/Processes.sq`

- [x] добавить `reparented_at TEXT` в `CREATE TABLE process` для новых баз
- [x] добавить запрос `markReparented` с условием `reparented_at IS NULL` для идемпотентности
- [x] комментарием объяснить, почему это отдельный запрос, а не расширение `insertProcess`
- [x] комментарием объяснить выбор TEXT вместо `REFERENCES sample(id)`: ретеншен удаляет сэмплы, CASCADE снёс бы процесс, SET NULL потерял бы факт
- [x] комментарием отметить коллизию терминологии: `orphan` в этой схеме уже значит «строка без ссылающихся детей» (`deleteOrphanApplications`), поэтому колонка называется `reparented_at`
- [x] тестов нет: задача определяет SQL и не несёт поведения. Поведение покрывают задачи 5 и 6
- [x] `./kotlin build`, затем сверить генерацию: колонка — в `build/tasks/_history-sqlite_generate@sqldelight-gen/dev/yoda/harmon/db/harmon/HarmonDatabaseImpl.kt` (там только DDL), сам запрос — в `.../db/ProcessesQueries.kt` (там тела запросов)

### Task 5: Механизм миграции и его тесты

**Files:**
- Modify: `history-sqlite/src/dev/yoda/harmon/history/HistoryStore.kt`
- Create: `history-sqlite/test/HistoryMigrationTest.kt`

- [x] добавить приватную функцию, читающую `PRAGMA table_info(process)` и возвращающую набор имён колонок
- [x] добавить выполнение `ALTER TABLE process ADD COLUMN reparented_at TEXT`, когда колонки нет
- [x] вызвать миграцию в новом `init` блоке `HistoryStore` (сегодня его нет), после создания `database` — сделано **иначе**: `init` не появился. Ревью показало, что миграция в `init` делает недостижимую базу фатальной для всего прогона, поэтому вызов живёт в `HistoryStore.openOrNull` (`HistoryStore.kt:478`) и повторяется из `record` через `migrateBeforeWriting` (`HistoryStore.kt:260`). Коммиты `9ca8472` и `d104f9a`
- [x] **не** оборачивать в `runCatching`: sqliter печатает полный стектрейс до броска, проглоченное исключение всё равно даст стену красного в launchd-логе на каждом старте агента
- [x] написать KDoc **на английском**: это первая миграция в проекте и почему `.sqm` для неё непригоден
- [x] написать хелпер, создающий базу **старой** формы: таблицу `process` построить сырым SQL по прежнему определению — это документирует дошаговую форму схемы там, где её больше нигде не видно
- [x] **обязательно выставить `PRAGMA user_version = 1`** после сырого DDL (или отдать старый DDL через `SqlSchema`-заглушку версии 1). Иначе sqliter увидит 0, выполнит `Schema.create()`, тот упадёт на уже существующей `process`, `openOrNull` проглотит исключение и вернёт **null** — тест провалится с «store is null» и не укажет на причину
- [x] переиспользовать существующий `withScratchHome` (`history-sqlite/test/TestFixtures.kt:359`), а не писать свой
- [x] написать тест: открытие store над базой старой формы добавляет колонку, `PRAGMA table_info` её показывает
- [x] написать тест: данные, записанные до миграции, уцелели и читаются
- [x] написать тест идемпотентности: повторное открытие не пытается добавить колонку снова и не бросает
- [x] написать тест: открытие пустой директории создаёт базу сразу с колонкой (путь `Schema.create`, без миграции)
- [x] ➕ по итогам трёх раундов ревью задача выросла за первоначальные рамки; фактически в `HistoryStore.kt` появились ещё: `MIGRATION_ATTEMPTS` (бюджет в три попытки на прогон), `isSchemaVerdict` (классификация по коду ошибки SQLite), `schemaAlreadyMigrated` (второе чтение таблицы: `duplicate column name` — это гонка с другим писателем, а не приговор), `HistorySchemaMismatch` и `abandonHistory` (лог один раз, закрытие драйвера, `record` дальше молчит)
- [x] ➕ и ещё семь тестов в `HistoryMigrationTest.kt` сверх четырёх запланированных: сэмпл после миграции ложится в расширенную таблицу; неремонтируемая схема даёт null-store и одну строку лога; недостижимая при открытии база сохраняет store и мигрирует на первой записи; `ALTER`, упавший на моменте, ретраится; постоянно падающая миграция сдаётся; колонка, добавленная другим писателем, считается успешной миграцией; бюджет — ровно одна попытка в `openOrNull` и две записи
- [x] `./kotlin build && ./kotlin test` — должны пройти до задачи 6

### Task 6: Запись отметки в HistoryRows и HistoryStore

**Files:**
- Modify: `history-sqlite/src/dev/yoda/harmon/history/HistoryStore.kt`
- Modify: `history-sqlite/test/HistoryProcessRowTest.kt`
- ~~Modify: `history-sqlite/src/dev/yoda/harmon/history/HistoryRows.kt`~~ — не потребовался, см. первый пункт

- [x] добавить в `HistoryRows.kt` функцию `ProcessesQueries.markReparented(processId, capturedAt)` — обёртка была написана и **удалена** в `934a30a` по замечанию ревью: она ничего не добавляла к сгенерированному запросу. `HistoryRows.kt` байт в байт совпадает с базовой веткой, `record` зовёт `processes.markReparented(...)` напрямую (`HistoryStore.kt:298`)
- [x] в цикле `record` (`HistoryStore.kt:136-143`) вынести id процесса в локальную переменную — сейчас он вычисляется инлайн внутри вызова, а отметке он нужен отдельно
- [x] вызвать `markReparented` в той же транзакции при условии `reparentedFrom != null && parentPid == 1`
- [x] использовать `usage.capturedAt.toSqlTimestamp()` — то же представление времени, что у `sample.captured_at`
- [x] обновить чтение строки процесса под новую колонку — правки не потребовалось: сгенерированный `Process` уже несёт `reparented_at` (`selectProcesses` разворачивает `SELECT *` по именам), а в `src/` эту строку никто не читает — `selectProcesses()` вызывается только из тестов, доменного маппинга у `process` нет
- [x] написать тест: сэмпл с переходом к `ppid=1` ставит `reparented_at`, равный `captured_at` этого сэмпла
- [x] написать тест: переход к родителю, отличному от 1, отметки **не** ставит
- [x] написать тест: сэмпл без перехода оставляет `reparented_at` равным null
- [x] написать тест: `parent_pid` остаётся прежним — виновник не затирается переходом
- [x] написать тест: повторный сэмпл с тем же переходом не перезаписывает уже проставленную отметку
- [x] `./kotlin build && ./kotlin test` — должны пройти до задачи 7

### Task 7: Ключ конфигурации для правила

**Files:**
- Modify: `core/src/dev/yoda/harmon/config/Config.kt`
- Modify: `core/src/dev/yoda/harmon/analysis/AlertAnalyzer.kt`
- Modify: `core/test/ConfigLoaderTest.kt`
- Modify: `core/test/AlertAnalyzerTest.kt`

- [x] добавить ключ, позволяющий выключить правило: каждое существующее правило гейтится nullable-порогом (`Config.kt:43-51`), и `README.md:250` обещает, что порог `0` отключает правило — безусловное правило нарушало бы этот контракт
- [x] у сиротства числового порога нет, поэтому ключ булев (`orphanAlerts`, по умолчанию `true`); отразить это в `describe()` рядом с остальными — ключ положен в `HarmonConfig` верхним уровнем рядом с `maxAlertsPerCategory`, а не в `AlertThresholds`: тот тип целиком состоит из nullable-чисел, с которыми сравнивают значение
- [x] добавить имя ключа в список известных, чтобы неизвестный ключ по-прежнему ловился валидацией
- [x] обернуть правило из задачи 3 этим гейтом
- [x] написать тест в `ConfigLoaderTest`: ключ парсится, дефолт `true`, мусорное значение отвергается
- [x] написать тест в `AlertAnalyzerTest`: при выключенном ключе сирота алерта не даёт
- [x] ➕ добавить ключ в `config/harmon.conf.example` — прецедент проекта для нового ключа (`docs/plans/completed/20260728-fix-review-findings.md:707`) требует трёх мест: `redactedDescription`, `knownKeys` и пример конфига
- [x] `./kotlin build && ./kotlin test` — должны пройти до задачи 8

### Task 8: Verify acceptance criteria

- [x] проверить, что все требования из Overview реализованы — детект в `UsageCalculator.reparentedFrom` (`UsageCalculator.kt:70-90`), питается циклом `run`, который переносит `previous` между сэмплами (`HarmonService.kt:102-106,118-126,195-199`); алерт в `AlertAnalyzer.orphanAlerts`; запись в `HistoryStore.record` → `markReparented` → `process.reparented_at`. Условие `reparentedFrom != null && parentPid == 1` продублировано ровно у двух потребителей, как и задумано. `ProcessUsage` и `ReparentedFrom` не `@Serializable`, в `core/src/.../ipc/` ни одного упоминания
- [x] проверить граничные случаи: процесс исчез между сэмплами; родителя не было в предыдущем снимке; переход к родителю, отличному от 1; каскад из десятков сирот
  - исчез между сэмплами — покрыт неявно, через `.processes.single()` в `UsageCalculatorTest.reportsThePreviousParentWhenItChanged` и `.reportsATransitionToAParentOtherThanPidOne`: в обоих `previous` несёт процесс, которого в `current` уже нет, и ассерт требует ровно одну строку на выходе. Отдельного теста с этим именем нет
  - родителя не было в предыдущем снимке — `UsageCalculatorTest.leavesTheParentNameNullWhenThePreviousSnapshotDidNotCarryIt` и `AlertAnalyzerTest.namesTheParentByPidAloneWhenThePreviousSampleDidNotHoldItsName`
  - переход к родителю, отличному от 1 — `UsageCalculatorTest.reportsATransitionToAParentOtherThanPidOne`, `AlertAnalyzerTest.raisesNoAlertWhenTheNewParentIsNotPidOne`, `HistoryProcessRowTest.aChangeToAnyOtherParentIsNotStamped`
  - ⚠️ каскад — покрыт на пяти сиротах при `maxAlertsPerCategory = 3` (`AlertAnalyzerTest.capsOrphanAlertsByPidAndSuppressesTheRest`, `.doesNotKeepASuppressedOrphanFiringForALaterSample`), а не на «десятках». `take`/`drop` от размера не зависят, так что поведение то же; теста именно на десятки нет
- [x] убедиться, что `bridge-probe`, IPC-протокол и `harmon-collector` не изменены — `git diff --stat feat/split-collector-binary` показывает 27 файлов (18 на момент первого прогона задачи; остальные добавили раунды ревью), ни одного под `bridge-*/`, `harmon-collector/` или `core/src/dev/yoda/harmon/ipc/`. `test/native/`, `scripts/test-native.sh`, `selftest/` и оба драйвера харнессов тоже не тронуты
- [x] прогнать полный набор: `./kotlin build && ./kotlin test` — `Build successful`; 5 + 72 + 182 + 76 = 335 тестов, `PASSED`, exit 0 (322 на момент первого прогона задачи)
- [x] прогнать релизную сборку: `./kotlin build --variant release` — `Build successful`, exit 0. Строки `'+zcm' is not a recognized feature for this target (ignoring feature)` — шум тулчейна при линковке модулей с `-lsqlite3`, к правкам ветки отношения не имеет и сборку не валит
- [x] прогнать нативные харнессы: `scripts/test-native.sh` — 90 проверок `ok`, exit 0, исходники харнессов относительно базовой ветки без изменений

### Task 9: [Final] Update documentation

- [x] `docs/history.md`: описать колонку `reparented_at` в разделе про `process`, рядом с объяснением, почему `parent_pid` замерзает — колонка добавлена в DDL-листинг, три абзаца после объяснения заморозки (кем пишется, почему отдельным запросом, почему TEXT, откуда имя), плюс абзац в «Three things the schema does not carry»: для этой строки замороженный `parent_pid` — единственный случай, где устаревшее значение полезно. ➕ добавлен готовый запрос в раздел «Queries»: без него колонку нечем достать, а весь документ построен как интерфейс для `sqlite3`
- [x] `docs/history.md:571`: переписать концовку раздела «Changing the schema» — «None of this is implemented» больше не соответствует действительности; заменить описанием реализованной миграции как образца для следующих — описан `migrateSchema`, который зовёт `openOrNull` (не `init`: его нет, см. задачу 5), чтение `PRAGMA table_info` через `executeQuery`, и три неочевидных следствия: sqliter не вызовет сгенерированную миграцию (`user_version` уже 1), открытие store перестало быть ленивым, `ADD COLUMN` не переписывает строки. Вводная фраза раздела «Nothing here is a migration yet» тоже поправлена
- [x] `docs/collection.md:471`: исправить утверждение, что cap оставляет «не более `maxAlertsPerCategory` **приложений** по метрике правила» — с этим правилом оно неверно; описать инлайн-cap по pid и то, что подавленный orphan теряется навсегда — cap переформулирован как «у каждого правила свой порядок ранжирования», добавлен абзац «для приложения это отсрочка, для сироты потеря» с оговоркой, что сам факт уцелевает в `alert` (`reported = 0`) и в `reparented_at`
- [x] `docs/collection.md` (раздел «Alerts», от `:458`): добавить правило в перечень — строка в таблице (`Orphaned process | on | never; always a warning`) и два абзаца: механика перехода, ключ, текст сообщения, фильтрация демонизации интервалом и её неприменимость к `once`/`diagnose`, слепое пятно рестарта
- [x] `README.md:50`: добавить сиротство в список того, на что harmon поднимает алерты
- [x] `README.md:287-310` («Alerts and notifications»): описать семантику одноразовой доставки и то, что ретрая у этого правила нет — отдельный абзац: почему ретрай неприменим (держится ключ, а пересобрать `Alert` нечем), подавленная сирота не демотируется а теряется, факт уцелевает в отчёте/`alert`/`reparented_at`. Абзац про edge detection дополнен тем, что в `once`/`diagnose` правило срабатывает, но их окно короче double fork
- [x] `README.md:239-250`: задокументировать новый ключ конфигурации — `orphanAlerts=true` в блоке дефолтов и абзац рядом с «A threshold of `0` disables that rule»
- [x] обновить `CLAUDE.md`, если по ходу работы обнаружились новые нетривиальные факты — кандидат уже есть: «`HarmonDatabaseImpl.kt` показывает только DDL, тела запросов лежат в `<Table>Queries.kt`» уточняет существующую фразу про генерацию — проверено на сгенерированном коде: `HarmonDatabaseImpl.kt` содержит только `object Schema` (DDL + версия), ни одного `SELECT`/`UPDATE`; `markReparented` и развёрнутый `SELECT *` — в `ProcessesQueries.kt`. Фраза переписана. Туда же добавлено, что `migrateSchema` — первая миграция и что её `PRAGMA table_info` сделал открытие store неленивым
- [ ] переместить этот план в `docs/plans/completed/` — отложено намеренно, а не выполнено: перенос делает харнесс после всех фаз ревью, ранний move сломал бы фазы, читающие план. Чекбокс остаётся пустым, пока файл лежит здесь

## Post-Completion

*Требует ручного вмешательства или внешних систем — без чекбоксов, справочно*

**Ручная проверка на живой машине:**

Детект наблюдаем только на настоящем переходе, поэтому финальная проверка — воспроизвести
его руками. Рецепт без установки launchd-сервисов (из `CLAUDE.md`):

```shell
# терминал 1: локальный непривилегированный коллектор
build/tasks/_harmon-collector_linkMacosArm64Debug/harmon-collector.kexe \
  --allow-unprivileged --socket /tmp/harmon-dev.sock \
  --allowed-uid "$(id -u)" --allowed-gid "$(id -g)"

# терминал 2: процесс-жертва с промежуточным родителем
bash -c 'sleep 100000 & echo "child $!"; echo "parent $$"; sleep 600'
```

Дать harmon снять хотя бы один сэмпл с живым родителем (при `intervalSeconds = 300` это
пять минут — для проверки интервал стоит временно уменьшить), затем убить родителя и
дождаться следующего сэмпла. Ожидается: алерт с pid ребёнка и pid убитого родителя,
и непустой `reparented_at` в базе.

Отдельно стоит убедиться, что штатная демонизация алерта **не** даёт: запустить
`tmux new -d -s probe` и убедиться, что за следующие несколько сэмплов ни одного
orphan-алерта не появилось.

**Оговорка про короткое окно:** аргумент про фильтрацию демонизации привязан к
`intervalSeconds = 300` и на `harmon once` / `diagnose` не распространяется — там окно
`SAMPLE_SECONDS_RANGE = 1..300` (`Config.kt:43`), и двухсекундный интервал заметно чаще
попадает ровно внутрь double fork. В историю это не пишется, но `once --notify`
доставляет, так что ложная сирота доходит до каналов уведомлений. Подробнее — в четвёртом
пункте «Принятые ограничения».

**Миграция существующей базы:**

На этой машине уже лежит `~/Library/Application Support/Harmon/history.db` размером
100 МБ с 25 814 строками в `process`. Перед первым запуском новой сборки стоит снять
копию, затем открыть агентом и убедиться, что миграция прошла без ошибок в launchd-логе,
а старые данные читаются. `ALTER TABLE ADD COLUMN` в SQLite не переписывает таблицу, так
что задержка на старте ожидается незаметной, но проверить это на реальном объёме дешевле,
чем узнать о проблеме из логов.

**Ветка:**

Работа идёт в `feat/orphan-detect`, отведённой от **`feat/split-collector-binary`**, а не
от `main`. Это не оплошность: `core/`, `history-sqlite/`, `harmon-collector/` и
`bridge-probe/` существуют только в split-ветке — в `main` ещё монолитная структура
(`src/`, `sqldelight/`, `nativebridge/`), и ни один путь из этого плана там не
разрешается. Следствие: работа попадёт в `main` только после мёржа тех 19 коммитов.

**Будущая работа (не входит в этот план):**

Зомби-детект через `pbi_status == SZOMB`. Поле уже лежит в `struct proc_bsdinfo`, которую
`bridge-probe/cinterop/harmon_probe.def:451` читает и из которой берёт только `pbi_ppid` и
`pbi_uid` — новых системных вызовов не нужно. Цена в другом: поле придётся протащить через
bridge и IPC-протокол, добавить C-чек в харнесс и снять соответствующую запись из списка
принятых пробелов в `docs/native-testing.md`.

Если одноразовая доставка окажется на практике болезненной, вернуться к идее удержания
алерта: она требует переиздавать объект `Alert` каждый сэмпл, пока процесс жив с
`ppid=1`. Это выполнимо без нового состояния, но повторное сообщение уже не сможет
назвать родителя, и правило будет занимать слот `maxAlertsPerCategory` всё время жизни
процесса.
