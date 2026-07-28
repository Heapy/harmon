# Fix code review findings

## Overview

Устранить 15 находок код-ревью по всему репозиторию плюс изменить семантику уведомлений:
пуш должен нести только **новые** алерты (переход через порог), а приложенный HTML/JSON-репорт —
**полную** картину сэмпла.

Что это чинит по существу:

- CRITICAL-алерты молча теряются, когда webhook/telegram упали, потому что системный канал всегда
  рапортует успех и запускает кулдаун;
- root-демон коллектора завершает процесс на любой транзиентной ошибке `accept`;
- агент опрашивает часы 100 раз в секунду вместо сна (при `systemNotifications=false` — весь
  интервал целиком; при включённых системных уведомлениях — минимум раз в секунду) в демоне,
  смысл которого в экономии батареи;
- агент падает целиком на исключении из любого шага после `capture()`;
- переполнение `ULong` превращает большой порог во всегда срабатывающий алерт;
- `substring` по индексу от `lowercase()` даёт пустые имена приложений и `StringIndexOutOfBounds`;
- кулдаун-мапа растёт неограниченно, а её ключи нестабильны для процессов без бандла;
- ~27 МБ malloc/free и до 10^6 системных вызовов на каждый сбор, внутри однопоточного accept-цикла.

## Context (from discovery)

- **Проект**: Kotlin/Native, Amper (`module.yaml`), `macosArm64`, Kotlin 2.4.10,
  `allWarningsAsErrors: true`. Сборка `./kotlin build`, тесты `./kotlin test`.
- **Файлы под правку**: `src/dev/yoda/harmon/{runtime,analysis,ipc,notify,monitor,report,cli,config}/`,
  `cinterop/harmon_native.def`, `config/harmon.conf.example`, `docs/collection.md`, `README.md`.
- **Тесты**: плоская директория `test/`, `kotlin.test`, общие фикстуры в `test/TestFixtures.kt`.
  Покрыты `analysis`, `report`, `config`, `cli`, `monitor`. Для `runtime`, `ipc`, `notify` тестов нет.
- **Паттерны**: инъекция зависимостей через параметры конструктора со значениями по умолчанию
  (`HarmonService`, `CollectorServer`, `UsageCalculator`, `DarwinSystemCollector`) — используем её
  для тестируемости вместо новых абстракций.

### Находка → задача

| # | Находка | Задача |
| --- | --- | --- |
| 1 | Системный канал всегда `successful = true`, кулдаун стартует при упавшей доставке | 5, 6 |
| 2 | `acceptClient` бросает исключение и роняет root-демон | 10 |
| 3 | `sleepSeconds` опрашивает часы вместо сна | 9 |
| 4 | Переполнение `ULong` в пороге MiB→байты | 3 |
| 5 | Дедлайн сна по настраиваемым стенным часам | 9 |
| 6 | Необёрнутые исключения после `capture()` убивают агент | 8 |
| 7 | `lowercase()`-индекс применён к исходной строке | 12 |
| 8 | Список терминалов из двух хардкодных имён | 13 |
| 9 | Мапа кулдауна растёт неограниченно, ключи нестабильны | 1, 6 |
| 10 | Шторм `proc_pidinfo` блокирует accept-цикл | 16 |
| 11 | Проверка версии протокола недостижима | 11 |
| 12 | AppKit поднимается для команд без уведомлений | 7 |
| 13 | ~27 МБ malloc/free на каждый сбор | 15 |
| 14 | CLI обходит границу 1..300 для `--sample-seconds` | 14 |
| 15 | Двойной рендер отчёта и повторные сортировки | 4 (рендер), 17 (сортировки) |

## Development Approach

- **testing approach**: TDD — в каждой задаче сначала падающий тест, воспроизводящий находку,
  затем правка. Порядок пунктов в чеклистах это отражает.
- complete each task fully before moving to the next
- make small, focused changes
- **CRITICAL: every task MUST include new/updated tests** for code changes in that task
  - tests are not optional - they are a required part of the checklist
  - write unit tests for new functions/methods
  - write unit tests for modified functions/methods
  - add new test cases for new code paths
  - update existing test cases if behavior changes
  - tests cover both success and error scenarios
- **CRITICAL: all tests must pass before starting next task** - no exceptions
- **CRITICAL: update this plan file when scope changes during implementation**
- run tests after each change
- maintain backward compatibility

**Порядок задач подобран так, чтобы модуль компилировался в конце каждой из них.** Удаление
`AlertCooldown` и поля `alertCooldownSeconds` живёт в той же задаче (6), которая переводит
`HarmonService` на новое состояние; новые параметры (`activeKeys`, `highlighted`, `reportText`)
добавляются с дефолтами, чтобы существующие вызовы продолжали компилироваться.

Единственное ломающее изменение — семантика уведомлений и удаление `alertCooldownSeconds`. Ключ
остаётся принимаемым конфиг-парсером как deprecated-игнорируемый (с предупреждением), чтобы
существующие `~/.config/harmon/config` не падали на unknown key.

## Testing Strategy

- **unit tests**: обязательны в каждой задаче (см. Development Approach)
- **e2e tests**: UI-тестов в проекте нет. Роль e2e играет локальный IPC smoke-тест из README
  (запуск `collector --allow-unprivileged` + `once --config`) — прогоняется вручную в задаче 18
- нативный C-код (`cinterop/harmon_native.def`) юнит-тестами не покрывается: для задач 15 и 16
  проверка — `harmon diagnose` на реальной машине с замером времени сбора
- **предпосылка, проверенная в задаче 1**: тестовый сорс-сет Amper **не** видит `internal`
  из `src/` (⚠️ в задаче 1) — хелперы, вынесенные ради тестируемости, делаются публичными

## Progress Tracking

- mark completed items with `[x]` immediately when done
- add newly discovered tasks with ➕ prefix
- document issues/blockers with ⚠️ prefix
- update plan if implementation deviates from original scope
- keep plan in sync with actual work done

## Solution Overview

**Алерты становятся событийными, а не таймерными.** `AlertCooldown` (мапа «когда последний раз
отправляли», растущая бесконечно) заменяется на `AlertState` с двумя ограниченными множествами
ключей. Пуш уходит на ключи, которых не было в прошлом сэмпле. Пока условие держится — молчим,
напоминаний нет. Дребезг вокруг порога гасится гистерезисом.

**Два множества, а не одно.** `firing` — ключи, чьё условие выполнялось в прошлом сэмпле;
коммитится безусловно на каждом сэмпле и кормит гистерезис. `notified` — ключи, по которым
доставка реально удалась; кормит выбор новых алертов. Если бы множество было одно, ключ с
провалившейся доставкой выпадал бы и из гистерезиса: значение между 90% и 100% порога перестало бы
считаться активным, алерт исчез бы совсем и никогда не был доставлен — ровно та тихая потеря,
которую чинит задача 5.

**Ключ коммитится в `notified` только после успешной доставки.** Если все решающие каналы упали,
свежие ключи не попадают в `notified` и повторно сработают на следующем сэмпле. Системный канал
(Notification Center) помечается `bestEffort = true` и не участвует в подсчёте успеха, когда есть
хотя бы один решающий канал.

**Пуш и репорт расходятся.** `ReportFormatter.notification(report, highlighted, reportText)`:
`title`/`subtitle`/`text` строятся из `highlighted` (новые алерты), а HTML-документ и JSON — из
полного `report`. Заодно снимается двойной рендер `text(report)`.

**Устойчивость.** Тело итерации выносится из `runForever` в тестируемую `handleSample` и целиком
берётся под `try/catch`; `sleepSeconds` считает дедлайн по `CLOCK_MONOTONIC` и реально спит;
`acceptClient` логирует и продолжает вместо того, чтобы убить root-демон.

**Стоимость сбора.** Массивы под процессы аллоцируются по реальному числу PID вместо фиксированных
16384/4096; обход VM-регионов получает бюджет системных вызовов на сэмпл.

### Key design decisions

| Решение | Почему |
| --- | --- |
| Edge detection без таймера повторов | Выбор пользователя: пуш строго на переход через порог |
| Два множества (`firing` / `notified`) | Иначе провал доставки выключает гистерезис и алерт теряется навсегда |
| Гистерезис 90% как константа, не конфиг | YAGNI — один разумный коэффициент, без нового ключа |
| `battery-low` исключён из гистерезиса | Единственное правило со сравнением «меньше либо равно»: множитель 0.9 работал бы в обратную сторону, снимая алерт при ещё выполненном условии. Процент заряда монотонен и не дребезжит |
| Активные ключи имеют приоритет в top-N категории | Иначе вытеснение за `maxAlertsPerCategory` выглядит как погасание и даёт ложный повторный пуш |
| `alertCooldownSeconds` — deprecated-ignored, не unknown | Не ломать существующие конфиги на старте демона |
| `bestEffort` флаг вместо проверки доставки в Notification Center | `deliverNotification` асинхронный и не даёт синхронного подтверждения |
| Частичный успех (webhook ok, telegram упал) считается успехом | Унаследовано от текущего `anySuccess`. Следствие с edge detection: упавший канал этот алерт больше не увидит. Осознанный размен: альтернатива — пер-канальное состояние, что утраивает сложность ради редкого случая |
| Слайсы сна вместо одного длинного | Позволяют перепроверить run loop после ленивой инициализации AppKit, не превращая флаг в необратимую защёлку |
| Бюджет syscall вместо переноса сбора из accept-цикла | Однопоточность коллектора — осознанная архитектура; бюджет ограничивает худший случай без переписывания IPC |
| `terminalApplications` в конфиге | Список терминалов зависит от пользователя, хардкод из двух имён заведомо неполон |

## Technical Details

### AlertState

```kotlin
class AlertState {
    private var firing: Set<String> = emptySet()    // условие выполнялось в прошлом сэмпле
    private var notified: Set<String> = emptySet()  // доставка подтверждена

    val activeKeys: Set<String> get() = firing      // для гистерезиса в AlertAnalyzer

    fun newlyActive(alerts: List<Alert>): List<Alert> = alerts.filter { it.key !in notified }

    fun commit(alerts: List<Alert>, deliveredKeys: Set<String>) {
        val keys = alerts.mapTo(mutableSetOf()) { it.key }
        firing = keys
        notified = keys intersect (notified + deliveredKeys)
    }
}
```

`commit` вызывается **на каждом сэмпле безусловно**, включая ветки без доставки и пустой диспетчер.
Иначе погасший ключ навсегда остаётся в `notified` и его повторное загорание не даст пуша — тот
самый баг, ради которого делается edge detection. Оба множества ограничены числом алертов сэмпла
(`maxAlertsPerCategory × число категорий`), поэтому неограниченный рост исключён по построению.

### Гистерезис в AlertAnalyzer

`analyze(usage, config, activeKeys: Set<String> = emptySet())`. Для кандидата с ключом из
`activeKeys` порог сравнения умножается на `CLEAR_RATIO = 0.9`; `severity` по-прежнему считается
от исходного порога. Применяется к `cpu`, `memory`, `disk-write`, `battery-impact`, `swap`,
`swap-out`. **Не применяется к `battery-low`** (см. таблицу решений).

Отбор top-N внутри категории: `sortedByDescending { value }.take(maxAlertsPerCategory)` плюс
кандидаты, чей ключ уже в `activeKeys`, но не попал в срез. Итоговый размер категории ограничен
`2 × maxAlertsPerCategory`.

### Пороги в байтах

`thresholdMiB.toULong() * BYTES_PER_MEBIBYTE` заменяется на насыщающее умножение
(`if (ULong.MAX_VALUE / BYTES_PER_MEBIBYTE < mib) ULong.MAX_VALUE else mib * BYTES_PER_MEBIBYTE`),
плюс верхняя граница в `ConfigLoader.validate`: `applicationMemoryAlertMiB` и `swapAlertMiB`
не больше `1_048_576` (1 ТиБ). Проверка `>= thresholdBytes * 2u` тоже насыщающая. Это двойная
защита: граница в конфиге закрывает путь из файла, насыщение — прямое конструирование
`AlertThresholds` из кода и тестов.

### Цикл агента

```kotlin
fun runForever(): Nothing {
    var previous = captureWithRetry()
    while (true) {
        sleepSeconds(config.intervalSeconds)
        val current = try { collector.capture() } catch (t: Throwable) { logError(...); continue }
        previous = current                    // до анализа, чтобы плохая пара не повторялась
        try { handleSample(previous, current) } catch (t: Throwable) { logError(...) }
    }
}

internal fun handleSample(previous: RawSystemSnapshot, current: RawSystemSnapshot) {
    val report = createReport(previous, current)   // analyze(usage, config, state.activeKeys)
    val text = ReportFormatter.text(report)
    log(text)
    deliverIfNeeded(report, text)
}
```

`handleSample` — точка входа для тестов задач 6 и 8: два вызова подряд воспроизводят
последовательность сэмплов без сна и без `runForever(): Nothing`.

### Сон агента

```kotlin
private fun sleepSeconds(seconds: Long) {
    val deadlineNs = hm_monotonic_time_ns() + seconds.toULong() * NANOS_PER_SECOND
    while (true) {
        val nowNs = hm_monotonic_time_ns()
        if (nowNs >= deadlineNs) return
        val remainingNs = deadlineNs - nowNs
        val sliceMs = minOf(remainingNs / NANOS_PER_MILLI, MAX_SLEEP_SLICE_MS)
        if (config.notifications.systemEnabled) {
            val result = CFRunLoopRunInMode(
                mode = kCFRunLoopDefaultMode,
                seconds = sliceMs.toDouble() / 1_000.0,
                returnAfterSourceHandled = false,
            )
            if (result != kCFRunLoopRunFinished) continue
        }
        hm_sleep_millis(sliceMs)
    }
}
```

Три изменения против текущего кода: дедлайн по `CLOCK_MONOTONIC`; `returnAfterSourceHandled = false`
(run loop не выходит после каждого события); при отсутствии источников спим слайс целиком, а не 10 мс.

`MAX_SLEEP_SLICE_MS = 30_000`. Флага-защёлки нет намеренно: `SystemNotificationChannel` создаётся
лениво (задача 7), и run loop получает источники только после первой доставки. Проба run loop на
каждом слайсе стоит один немедленный возврат и оставляет рабочим клик «Open report» по уведомлению
(README:206-210). Итог: около 10 пробуждений за пятиминутный интервал вместо ~30 000.

### Формат уведомления

```kotlin
fun notification(
    report: MonitoringReport,                   // полный: HTML + JSON
    highlighted: List<Alert> = report.alerts,   // только новые: title/subtitle/text
    reportText: String = text(report),          // уже отрендеренный текст, чтобы не рендерить дважды
): NotificationPayload
```

В JSON добавляется `newAlertKeys: List<String>` — потребитель webhook видит и полную картину,
и что именно изменилось.

### Учёт успеха доставки

`NotificationDispatcher.deliver` строит результаты через `channels.map { ... }`, то есть порядок
результатов совпадает с порядком каналов. Сопоставление по индексу даёт `bestEffort` без изменения
модели `DeliveryResult`:

```kotlin
fun decisiveSuccess(results: List<DeliveryResult>): Boolean {
    val decisive = results.filterIndexed { index, _ -> !channels[index].bestEffort }
    return decisive.isEmpty() || decisive.any { it.successful }
}
```

### Нативный слой

- новый `hm_count_processes()` → `proc_listallpids(NULL, 0)`; Kotlin аллоцирует
  `minOf(processCapacity, max(MIN_CAPACITY, count + HEADROOM))`. Альтернатива «просто снизить
  `DEFAULT_PROCESS_CAPACITY` до 2048» отвергнута: она меняет корректность (обрезание списка
  процессов) ради экономии трёх строк C;
- `hm_list_processes(...)` получает параметр `attribution_region_budget`; обход регионов
  прекращается, когда бюджет исчерпан. Семантика счётчиков сохраняется строго:
  процесс, обход которого оборвался посередине, **не** помечается `compressed_attribution_available`;
  процесс, до которого бюджет не дошёл, **не** помечается `compressed_attribution_attempted`;
- `HM_MAX_VM_REGIONS` остаётся абсолютным предохранителем; рабочий per-process лимит — 8192.

## What Goes Where

- **Implementation Steps** (`[ ]`): всё, что делается в этом репозитории — код, тесты, документация
- **Post-Completion** (без чекбоксов): ручная проверка на живой машине, переустановка launchd-сервисов

## Implementation Steps

### Task 1: Добавить AlertState с двумя множествами

**Files:**
- Create: `src/dev/yoda/harmon/analysis/AlertState.kt`
- Create: `test/AlertStateTest.kt`

- [x] убедиться, что тест из `test/` видит `internal`-объявление из `src/` (пробный тест на
      `HtmlReportStore`); если нет — зафиксировать ⚠️ и делать хелперы публичными
- [x] написать тест: ключ, отсутствовавший в прошлом сэмпле, попадает в `newlyActive`
- [x] написать тест: ключ, доставленный и подтверждённый `commit`, в `newlyActive` не попадает
- [x] написать тест: ключ, погасший на сэмпле, при следующем загорании снова считается новым
- [x] написать тест: свежий ключ с пустым `deliveredKeys` остаётся новым на следующем сэмпле
- [x] написать тест: `activeKeys` содержит ключ с провалившейся доставкой (гистерезис не выключается)
- [x] написать тест: оба множества не растут при потоке одноразовых ключей `process:<pid>:<startedAt>`
- [x] реализовать `AlertState` с `newlyActive`, `commit`, `activeKeys`
- [x] запустить `./kotlin test` — должно пройти до перехода к задаче 2

⚠️ **Тестовый сорс-сет НЕ видит `internal` из `src/`.** Пробный тест на `HtmlReportStore` не
компилируется: `cannot access 'class HtmlReportStore : Any': it is internal in file`. Amper не
объявляет `associate`-связь между main- и test-компиляциями Kotlin/Native. Следствие для
задач 6, 8, 9, 10, 15, 16: все хелперы, вынесенные ради тестируемости (`handleSample`,
`sleepSliceMillis`, `classifyAccept`, `processCapacityFor`), должны быть **публичными**, а не
`internal`. В плане ниже слово `internal` в этих пунктах читать как `public`.

➕ `AlertState` получил публичное read-only свойство `notifiedKeys` — без него инвариант
«оба множества ограничены» из чеклиста нельзя проверить тестом (`notified` приватно, а
`internal` тестам недоступно).

### Task 2: Добавить гистерезис и приоритет активных ключей в AlertAnalyzer

**Files:**
- Modify: `src/dev/yoda/harmon/analysis/AlertAnalyzer.kt`
- Modify: `test/AlertAnalyzerTest.kt`
- Modify: `docs/collection.md`

- [x] написать тест: значение между 90% и 100% порога даёт алерт, если ключ был в `activeKeys`
- [x] написать тест: то же значение без `activeKeys` алерта не даёт
- [x] написать тест: значение ниже 90% порога гасит алерт даже при наличии ключа в `activeKeys`
- [x] написать тест: `severity` считается от исходного порога, а не от сниженного
- [x] написать тест: `battery-low` при 19% и пороге 20 **удерживается**, а не гаснет (гистерезис
      не применён — см. ⚠️ ниже)
- [x] написать тест: 4 приложения над порогом при `maxAlertsPerCategory=3` — активный ключ,
      вытесненный из top-3, остаётся в списке алертов
- [x] добавить параметр `activeKeys: Set<String> = emptySet()` и константу `CLEAR_RATIO = 0.9`
- [x] применить гистерезис ко всем категориям, кроме `battery-low`
- [x] дополнять срез top-N активными ключами категории (потолок `2 × maxAlertsPerCategory`)
- [x] обновить таблицу порогов в `docs/collection.md:330-347` — описать гистерезис
- [x] запустить `./kotlin test` — должно пройти до перехода к задаче 3

⚠️ Формулировка пункта про `battery-low` в исходном чеклисте была инвертирована. По таблице
решений («множитель 0.9 работал бы в обратную сторону, снимая алерт при ещё выполненном условии»)
корректное поведение: при 19% и пороге 20 алерт **остаётся**, потому что гистерезис к правилу не
применяется. Тест `keepsLowBatteryAlertBecauseHysteresisIsNotAppliedToIt` проверяет именно это.

➕ Гистерезис для порога в байтах (`memory`) считается целочисленно (`/10*9`), а не
`toDouble() * CLEAR_RATIO`: нет риска переполнения при `ULong.MAX_VALUE` (задача 3 вводит
насыщение) и не нужна конверсия `Double → ULong`.

### Task 3: Убрать переполнение ULong в порогах и добавить верхние границы

**Files:**
- Modify: `src/dev/yoda/harmon/analysis/AlertAnalyzer.kt`
- Modify: `src/dev/yoda/harmon/config/Config.kt`
- Modify: `test/AlertAnalyzerTest.kt`
- Modify: `test/ConfigLoaderTest.kt`
- Modify: `docs/collection.md`

- [x] написать тест: `AlertThresholds(applicationMemoryMiB = 2^44)`, сконструированный напрямую,
      не даёт CRITICAL-алертов на пустой памяти
- [x] написать тест: то же для `swapUsedMiB`
- [x] написать тест: `ConfigLoader` отвергает `applicationMemoryAlertMiB` и `swapAlertMiB` больше 1048576
- [x] заменить умножение MiB→байты на насыщающее в обеих ветках (память приложения и swap)
- [x] сделать насыщающей проверку `>= thresholdBytes * 2u` для CRITICAL
- [x] добавить верхние границы обоих ключей в `ConfigLoader.validate`
- [x] отразить границу 1048576 MiB в описании порогов `docs/collection.md`
- [x] запустить `./kotlin test` — должно пройти до перехода к задаче 4

➕ Три дополнительных теста сверх чеклиста:
`doesNotOverflowTheDoubledThresholdWhenGradingSeverity` (порог 2^43 MiB даёт ровно 2^63 байт —
умножение не насыщается, а удвоение переполнялось бы в ноль и делало любой алерт CRITICAL;
единственный тест, покрывающий пункт про `>= thresholdBytes * 2u`),
`acceptsMemoryAndSwapThresholdsAtOneTebibyte` (граница включающая),
и позитивные утверждения внутри обоих overflow-тестов — без них тест прошёл бы вхолостую,
если бы хелпер случайно отключил проверяемое правило.

➕ Тестовый хелпер `singleThreshold` в `AlertAnalyzerTest`: конфиг со всеми правилами, кроме
одного, выключенными — иначе дефолтные пороги CPU/battery подмешивают чужие алерты в
`assertEquals(emptyList(), …)`.

### Task 4: Разделить содержимое пуша и приложенного репорта

**Files:**
- Modify: `src/dev/yoda/harmon/report/ReportFormatter.kt`
- Modify: `src/dev/yoda/harmon/report/ReportJson.kt`
- Modify: `test/ReportFormatterTest.kt`
- Modify: `test/ReportJsonTest.kt`

- [x] написать тест: при 1 новом и 5 активных алертах `payload.text` содержит только новый
- [x] написать тест: при тех же данных `payload.html` содержит все 5 в секции `Alerts:`
- [x] написать тест: `payload.json` содержит все 5 алертов и `newAlertKeys` из одного ключа
- [x] написать тест: `notification(report)` без `highlighted` ведёт себя как раньше
- [x] написать тест: переданный `reportText` попадает в HTML без повторного рендера
      (маркерная строка вместо настоящего отчёта)
- [x] изменить сигнатуру на `notification(report, highlighted = report.alerts, reportText = text(report))`
- [x] строить `title`/`subtitle`/`text` из `highlighted`, HTML и JSON — из полного `report`
- [x] добавить `newAlertKeys` в DTO `ReportJson`
- [x] запустить `./kotlin test` — должно пройти до перехода к задаче 5

➕ Общая фикстура `alert(key, severity, title, message)` вынесена в `test/TestFixtures.kt` —
нужна и в `ReportFormatterTest`, и в `ReportJsonTest`.

➕ `ReportJson.encode` и `ReportFormatter.json` получили параметр
`newAlertKeys: List<String> = report.alerts.map { it.key }`. Дефолт «все алерты новые», а не
пустой список: без edge detection (прямой вызов `json(report)`, `harmon once`) вся выборка и есть
то новое, что видит потребитель. Тест `treatsEveryAlertAsNewWhenTheCallerDoesNotSayOtherwise`
фиксирует это.

### Task 5: Пометить системный канал как best-effort и починить учёт успеха

**Files:**
- Modify: `src/dev/yoda/harmon/notify/NotificationChannels.kt`
- Create: `test/NotificationDispatcherTest.kt`

- [x] написать тест на фейковых каналах: упавший webhook + «успешный» best-effort канал →
      `decisiveSuccess == false`
- [x] написать тест: единственный best-effort канал → `decisiveSuccess == true`
- [x] написать тест: успешный webhook + упавший telegram → `decisiveSuccess == true`
- [x] написать тест: исключение из канала по-прежнему даёт `DeliveryResult(successful = false)`
- [x] добавить `val bestEffort: Boolean get() = false` в интерфейс `NotificationChannel`,
      переопределить в `SystemNotificationChannel` (реальный инстанс в тестах не создаём — только фейки)
- [x] добавить `NotificationDispatcher.decisiveSuccess(results)` с сопоставлением по индексу
- [x] уточнить `detail` системного канала — «queued in Notification Center», без утверждения о доставке
- [x] запустить `./kotlin test` — должно пройти до перехода к задаче 6

➕ Два теста сверх чеклиста: `everyDecisiveChannelFailingIsAFailure` (обе решающие доставки упали →
`decisiveSuccess == false`) и `emptyDispatcherIsNotTreatedAsAFailedDelivery` — последний фиксирует
контракт, на который опирается пункт «пустой диспетчер не блокирует обновление состояния» из задачи 6.

➕ `detail` системного канала: «queued in Notification Center (no delivery confirmation)» — явная
причина, почему канал best-effort, вместо просто снятого утверждения о доставке.

### Task 6: Перевести HarmonService на AlertState и удалить кулдаун

**Files:**
- Modify: `src/dev/yoda/harmon/runtime/HarmonService.kt`
- Modify: `src/dev/yoda/harmon/config/Config.kt`
- Delete: `src/dev/yoda/harmon/analysis/AlertCooldown.kt`
- Create: `test/HarmonServiceAlertFlowTest.kt`
- Modify: `test/ConfigLoaderTest.kt`
- Modify: `config/harmon.conf.example`
- Modify: `docs/collection.md`
- Modify: `README.md`

- [x] написать тест: два вызова `handleSample` с одним и тем же алертом дают одну доставку
- [x] написать тест: при `decisiveSuccess == false` алерт доставляется повторно на следующем сэмпле
- [x] написать тест: алерт погас на сэмпле без доставок и загорелся снова → приходит пуш
      (проверяет безусловный `commit`)
- [x] написать тест: `notifyEverySample = true` шлёт каждый сэмпл и передаёт в пуш все алерты
- [x] написать тест: пустой диспетчер не блокирует обновление состояния
- [x] написать тест: конфиг со строкой `alertCooldownSeconds=1800` парсится, значение игнорируется,
      предупреждение уходит в инъектированный `warn`
- [x] вынести тело итерации в `internal fun handleSample(previous, current)`, `runForever` свести
      к сну + `capture` + вызову `handleSample` (публичная — см. ⚠️ задачи 1)
- [x] заменить `AlertCooldown` на `AlertState`, передавать `state.activeKeys` в `analyzer.analyze`
- [x] переписать `deliverIfNeeded`: `newlyActive` → доставка → `commit(alerts, deliveredKeys)`,
      причём `commit` вызывается на каждом сэмпле, включая ранние выходы
- [x] передавать в `ReportFormatter.notification` `highlighted` и уже отрендеренный текст
- [x] удалить `AlertCooldown.kt`
- [x] удалить `alertCooldownSeconds` из `HarmonConfig`, `redactedDescription`, парсинга и `validate`
- [x] удалить осиротевшую `nonNegativeLong` из `Config.kt` (иначе unused private → ошибка при `allWarningsAsErrors`)
- [x] добавить `deprecatedKeys = setOf("alertCooldownSeconds")` и параметр `warn: (String) -> Unit = ::printError`
- [x] убрать ключ из `config/harmon.conf.example`, обновить `docs/collection.md:347`
      и упоминания кулдауна в `README.md:46,259`
- [x] запустить `./kotlin test` — должно пройти до перехода к задаче 7

➕ `deprecatedKeys` — мапа `ключ → причина`, а не `setOf`: предупреждение объясняет, почему ключ
больше не нужен («alerts now fire when a threshold is crossed»), иначе пользователю непонятно,
чем его заменить. Стоимость — одна строка.

➕ Пустой диспетчер не даёт наблюдаемой доставки, поэтому тест
`anEmptyDispatcherDoesNotBlockTheStateUpdate` наблюдает обновление состояния через гистерезис:
второй сэмпл (1950 MiB при пороге 2048) остаётся алертящим только потому, что первый закоммитил
ключ в `firing`. Парный контрольный тест
`aFootprintBelowTheThresholdAlertsOnlyBecauseOfTheCommittedState` показывает, что тот же сэмпл
в одиночку алерта не даёт — без него первый тест прошёл бы вхолостую.

➕ В `README.md` добавлен абзац рядом с описанием legacy-алиасов: `alertCooldownSeconds`
принимается, игнорируется и репортится в stderr. Без него из README не следует, что старый
конфиг не сломает старт агента.

### Task 7: Создавать NotificationDispatcher лениво

**Files:**
- Modify: `src/dev/yoda/harmon/runtime/HarmonService.kt`
- Modify: `test/HarmonServiceAlertFlowTest.kt`

- [x] написать тест: `sampleOnce()` с фейковым коллектором не обращается к `Lazy`-диспетчеру
      (счётчик вычислений инициализатора равен нулю)
- [x] написать тест: `handleSample` с новым алертом и непустым диспетчером к нему обращается
- [x] заменить default-аргумент `notifications` на `Lazy<NotificationDispatcher>`
- [x] обращаться к диспетчеру только в `deliver`, `testNotifications` и ветке доставки `deliverIfNeeded`
      (проверка `isEmpty` не должна стоять до решения о доставке)
- [x] проверить вручную, что `harmon once` без `--notify`, `harmon diagnose` и `harmon check-config`
      не поднимают AppKit
- [x] запустить `./kotlin test` — должно пройти до перехода к задаче 8

➕ Третий тест сверх чеклиста — `aSampleWithoutNewAlertsLeavesTheDispatcherUnbuilt`: именно он
сторожит порядок «решение о доставке → чтение `Lazy`». Первые два теста прошли бы и с
`isEmpty`-проверкой на прежнем месте, потому что тихий сэмпл с непустым диспетчером в них
не встречается.

➕ Тело `deliverIfNeeded` разделено на `deliverIfNeeded` (безусловный `commit`) и
`deliverSample` (возвращает подтверждённые ключи). Иначе после переноса `isEmpty`-проверки
получалось два одинаковых блока `commit(alerts, emptySet()); return`, и инвариант «коммит на
каждом сэмпле» размазывался по трём выходам вместо одного.

⚠️ Проверка «не поднимают AppKit» сделана статически (в `HarmonService` ровно три чтения
`notifications.value` — `testNotifications`, `deliver`, `deliverSample`, и ни одно не лежит на
пути `once`/`diagnose`/`check-config`) плюс рантайм-подтверждение: `harmon diagnose` с
`systemNotifications=true` живёт с тремя потоками и без единого кадра `NSApplication`/
`NSEventThread` в `sample`. `once --notify` не проверялся — он обязан поднимать AppKit.

### Task 8: Не ронять агент на исключении в цикле

**Files:**
- Modify: `src/dev/yoda/harmon/runtime/HarmonService.kt`
- Modify: `src/dev/yoda/harmon/monitor/UsageCalculator.kt`
- Modify: `test/UsageCalculatorTest.kt`
- Modify: `test/HarmonServiceAlertFlowTest.kt`

- [x] написать тест: два снимка с одинаковым `monotonicTimeNs` дают `CollectionException`
      с внятным сообщением, а не `IllegalArgumentException`
- [x] написать тест: исключение из доставки логируется, а `handleSample` не пробрасывает его наружу
- [x] написать тест: после исключения на одном сэмпле следующий сэмпл обрабатывается нормально
- [x] обернуть вызов `handleSample` в `try/catch` с логированием
- [x] обновлять `previous = current` сразу после успешного `capture()`, до анализа
      (уже было сделано в задаче 6; добавлен комментарий, объясняющий зачем)
- [x] заменить `require` в `UsageCalculator` на явный `CollectionException`
- [x] запустить `./kotlin test` — должно пройти до перехода к задаче 9

➕ Два уровня перехвата вместо одного. `try/catch` вокруг `handleSample` в `runForever` из плана
недостаточен для чекбокса «`handleSample` не пробрасывает исключение наружу»: он ловит уже снаружи
метода, а безусловный `commit` при этом теряется — состояние застревает на предыдущем сэмпле, и
после задач 6/7 это ровно тот тихий сбой, который чинит edge detection. Поэтому `deliverIfNeeded`
ловит бросок из `deliverSample` (реальный источник — ленивое построение диспетчера, поднимающее
AppKit), логирует его и коммитит с пустым множеством доставленных ключей; `try/catch` в
`runForever` остаётся внешним поясом для `createReport` и `ReportFormatter.text`.

➕ Тест `handlesTheNextSampleAfterADeliveryThrows` покрывает оба пункта разом: второй сэмпл
(1950 MiB при пороге 2048) доставляется только потому, что упавший первый всё-таки закоммитил ключ
в `firing`. Парный контрольный тест — `aFootprintBelowTheThresholdAlertsOnlyBecauseOfTheCommittedState`
из задачи 6.

➕ Второй тест на `UsageCalculator` — `rejectsSnapshotsInReverseOrder`: правка меняет `require(a > b)`
на `if (a <= b) throw`, и без него ветка «снимки переставлены местами» осталась бы непокрытой.

### Task 9: Заменить опрос часов монотонным сном

**Files:**
- Modify: `src/dev/yoda/harmon/runtime/HarmonService.kt`
- Create: `test/SleepSliceTest.kt`

- [x] написать тест на `internal fun sleepSliceMillis(remainingNs, maxSliceMs)`: остаток 0 → 0,
      остаток больше слайса → слайс, остаток меньше слайса → остаток, остаток меньше миллисекунды → 1
- [x] вынести расчёт слайса в `internal` функцию (публичную — см. ⚠️ задачи 1)
- [x] заменить `CFAbsoluteTimeGetCurrent` на монотонные часы при расчёте дедлайна
      (`TimeSource.Monotonic`, а не `hm_monotonic_time_ns()` — см. ⚠️ ниже)
- [x] заменить `returnAfterSourceHandled = true` на `false`
- [x] спать весь слайс при `kCFRunLoopRunFinished` вместо фиксированных 10 мс,
      run loop пробовать заново на следующем слайсе (защёлки нет — AppKit инициализируется лениво)
- [x] обходить run loop полностью при `systemNotifications = false`
- [x] проверить вручную: `harmon run` с `systemNotifications=false` и `intervalSeconds=60` —
      около 0% CPU в `top`; с включёнными уведомлениями клик «Open report» продолжает работать
      (клик — не автоматизируется, см. ⚠️ ниже)
- [x] запустить `./kotlin test` — должно пройти до перехода к задаче 10

⚠️ **Нативный мост недоступен из тестового бинаря.** `hm_monotonic_time_ns()`/`hm_sleep_millis()` из
плана роняют тест `sampleOnceNeverBuildsTheDispatcher` (он реально спит внутри `sampleOnce`):
`kotlin.internal.IrLinkageError: Function 'hm_monotonic_time_ns' can not be called`. Тестовый
бинарь не линкует cinterop-клиб проекта — `harmon_test.klib` зависит от `harmon`, но `harmon_native`
в линковку тестов не попадает (проверено после `./kotlin clean`; в `nm` тестового `.kexe` нет ни
одного `*_wrapper` из `nativebridge`). Платформенные библиотеки (`CoreFoundation`, `posix`)
линкуются нормально. Поэтому дедлайн считается по `kotlin.time.TimeSource.Monotonic`
(на Kotlin/Native это тот же `CLOCK_MONOTONIC`), а слайс спится через `platform.posix.usleep`.
Семантика плана сохранена целиком: монотонный дедлайн, реальный сон на весь слайс, проба run loop
на каждом слайсе. Следствие для задач 15 и 16: любой код, вызывающий `nativebridge`, из тестов
недостижим — тестировать можно только чистые хелперы (`processCapacityFor`), что план и предполагает.

⚠️ Ручная проверка: агент собран, коллектор поднят локально
(`collector --allow-unprivileged --socket /tmp/harmon-dev.sock`). При `systemNotifications=false`,
`intervalSeconds=60` — `0.0% CPU`, состояние `sleeping`, +10 мс CPU за 20 с сна. При
`systemNotifications=true`, `intervalSeconds=10` — три сэмпла подряд (01:53:19, 01:53:29, 01:53:40)
и `0.22 с` CPU за 35 с, включая загрузку AppKit на первой доставке: после появления источников
run loop цикл не начинает крутиться. Сам клик по уведомлению требует человека и не
автоматизируется; косвенное подтверждение — уведомление доставлено и
`~/Library/Application Support/Harmon/Reports/latest.html` перезаписан на той же секунде.

### Task 10: Не убивать демон коллектора на ошибке accept

**Files:**
- Modify: `src/dev/yoda/harmon/ipc/CollectorSocket.kt`
- Create: `test/CollectorAcceptOutcomeTest.kt`

- [x] написать тест на `internal fun classifyAccept(result, consecutiveFailures)`:
      `>= 0` → принять и сбросить счётчик, `-2` → отклонить без инкремента счётчика,
      `-1` → залогировать, инкрементировать, продолжить
- [x] написать тест: N подряд отказов авторизации (`-2`) не приводят к фатальному исходу
- [x] написать тест: `CONSECUTIVE_ACCEPT_FAILURE_LIMIT` подряд ошибок `-1` даёт фатальный исход
- [x] вынести классификацию в `internal` функцию и покрыть её тестами
      (публичную — см. ⚠️ задачи 1)
- [x] заменить `throw` в ветке `else` на логирование и продолжение цикла
- [x] добавить паузу `hm_sleep_millis` между подряд идущими ошибками
- [x] запустить `./kotlin test` — должно пройти до перехода к задаче 11

➕ Классификация вернулась не enum'ом, а `AcceptOutcome(decision, consecutiveFailures)`:
счётчик подряд идущих ошибок — часть решения (`SERVE` его обнуляет, `REJECT` не трогает,
`RETRY`/`FATAL` инкрементируют), и без него в результате инвариант «отказ авторизации не
приближает демон к смерти» тестом не проверяется. Сам счётчик живёт локальной переменной в
`runForever`, состояния в `CollectorServer` не появилось.

➕ Тело обслуживания клиента вынесено из `runForever` в `private fun serveClient(descriptor)` —
`when` по четырём исходам плюс прежний вложенный `try/catch/finally` в одном методе давали
пять уровней вложенности.

➕ Тест `oneServedClientBuysTheWholeFailureBudgetBack` сверх чеклиста: без него пункт про сброс
счётчика проверялся бы только на одиночном вызове, а не на последовательности «LIMIT-1 ошибок,
успех, снова ошибка → всё ещё RETRY».

➕ `CONSECUTIVE_ACCEPT_FAILURE_LIMIT = 16`, пауза `ACCEPT_FAILURE_PAUSE_MILLISECONDS = 100`
(≈1.6 с до фатального исхода). Значения в плане не заданы; выбраны так, чтобы `EMFILE` под
нагрузкой пережидался, а действительно сломанный слушающий сокет не крутился в логах вечно.

### Task 11: Сделать проверку версии протокола достижимой

**Files:**
- Modify: `src/dev/yoda/harmon/ipc/CollectorProtocol.kt`
- Modify: `test/CollectorProtocolTest.kt`

- [x] написать тест: payload с `protocolVersion = 2` и неизвестным полем даёт сообщение про
      неподдерживаемую версию, а не про невалидный JSON
- [x] написать тест: payload версии 1 с неизвестным полем по-прежнему отвергается
- [x] написать тест: корректный payload версии 1 декодируется без изменений
- [x] написать тест: невалидный JSON даёт `CollectorProtocolException` про невалидный JSON
- [x] парсить payload один раз в `JsonElement`, читать `protocolVersion`, декодировать через
      `decodeFromJsonElement` (двойного прохода по кадру до 32 МиБ быть не должно)
- [x] запустить `./kotlin test` — должно пройти до перехода к задаче 12

➕ Чтение версии вынесено в `private fun protocolVersionOf(element): Int?`, и версия проверяется
только когда поле — незакавыченный integer. Отсутствующее, строковое или дробное значение
пропускается к строгому декодеру: тот назовёт конкретное поле, тогда как ранняя проверка была бы
вынуждена сообщать про «версию», которой в кадре нет. Ветка «неподдерживаемая версия» при этом
остаётся достижимой — именно ради неё задача и делалась.

➕ `decodeFromJsonElement` вызван с явным `CollectorEnvelope.serializer()`, а не через
reified-расширение: `CollectorEnvelope` приватный, а член `Json` не требует лишнего импорта
(`kotlinx.serialization.decodeFromString` из импортов удалён — иначе unused import при
`allWarningsAsErrors`).

### Task 12: Починить поиск .app-бандла по Unicode-пути

**Files:**
- Modify: `src/dev/yoda/harmon/analysis/ApplicationGrouper.kt`
- Modify: `test/ApplicationGrouperTest.kt`

- [x] написать тест: путь `/Applications/İstanbul.app/Contents/MacOS/app` даёт имя `İstanbul`
- [x] написать тест: путь `/İİİ.app/x` не бросает `StringIndexOutOfBoundsException`
      (при трёх и более символах смещение индекса превышает длину исходной строки)
- [x] написать тест: путь `/İİ.app/x` не даёт пустого имени приложения
- [x] написать тест: регистронезависимость сохраняется (`/Applications/Foo.APP/Contents/MacOS/foo`)
- [x] заменить `lowercase().indexOf(...)` на `indexOf(APP_BUNDLE_MARKER, ignoreCase = true)`
      по исходной строке
- [x] добавить защиту от выхода за границы и пустого имени приложения
- [x] запустить `./kotlin test` — должно пройти до перехода к задаче 13

⚠️ Отдельной проверки границ не потребовалось: после перехода на `indexOf(..., ignoreCase = true)`
по исходной строке маркер `.app/` (5 символов) гарантированно лежит целиком внутри строки, поэтому
`markerIndex + 4` не может выйти за её длину. Явный `coerceAtMost(length)` был бы мёртвой веткой,
непокрываемой тестом. Оставшаяся часть пункта — защита от пустого имени — реализована как
`takeIf { it.applicationName().isNotEmpty() }`.

➕ Пятый тест сверх чеклиста — `treatsAPathWithAnEmptyBundleNameAsUnbundled` (`/.app/helper`):
после правки все три Unicode-теста дают непустое имя, и ветка защиты от пустого имени осталась бы
непокрытой. Вырожденный путь без имени бандла трактуется как процесс без бандла (`bundlePath = null`,
ключ `process:<pid>:<startedAt>`), а не как бандл с пустым именем — иначе все такие процессы
слились бы в одну группу по общему хешу.

➕ Тест `matchesTheBundleMarkerRegardlessOfItsCase` проходил и до правки (`Foo.APP` при
`lowercase()` не меняет длину) — он остаётся регрессионным сторожем для `ignoreCase = true`.

### Task 13: Вынести список терминалов в конфиг

**Files:**
- Modify: `src/dev/yoda/harmon/config/Config.kt`
- Modify: `src/dev/yoda/harmon/analysis/ApplicationGrouper.kt`
- Modify: `src/dev/yoda/harmon/monitor/UsageCalculator.kt`
- Modify: `src/dev/yoda/harmon/runtime/HarmonService.kt`
- Modify: `test/ApplicationGrouperTest.kt`
- Modify: `test/ConfigLoaderTest.kt`
- Modify: `config/harmon.conf.example`
- Modify: `README.md`
- Modify: `docs/collection.md`

- [x] написать тест: процесс, запущенный из `Terminal.app`, не наследует бандл терминала
- [x] написать тест: `terminalApplications=foo,bar` из конфига заменяет список целиком
- [x] написать тест: пустое значение ключа отключает границу терминалов
- [x] написать тест: `ApplicationGrouper()` без аргументов сохраняет поведение по умолчанию
      (`test/TestFixtures.kt:258` вызывает его именно так)
- [x] добавить ключ `terminalApplications` (список через запятую) с дефолтом
      `terminal, iterm2, iterm, alacritty, wezterm, kitty, ghostty, warp, hyper, tabby, agterm`
- [x] пробросить список через конструкторы `ApplicationGrouper` и `UsageCalculator` (с дефолтами)
- [x] добавить ключ в `redactedDescription`, `knownKeys`, `config/harmon.conf.example`
- [x] обновить `README.md:44` и `README.md:160-180` (блок defaults), `docs/collection.md:287-289`
- [x] запустить `./kotlin test` — должно пройти до перехода к задаче 14

➕ Дефолтный список живёт в `config/Config.kt` как публичная top-level `DEFAULT_TERMINAL_APPLICATIONS`,
а не в компаньоне `ApplicationGrouper`: это конфиг-дефолт наравне с порогами, и направление
зависимостей остаётся прежним (`analysis` → `config`, как в `AlertAnalyzer`). Обратное размещение
дало бы цикл `config` → `analysis`.

➕ `UsageCalculator` получил `terminalApplications` **первым** параметром, а прежний
`applicationGrouper` остался вторым со значением `ApplicationGrouper(terminalApplications)`:
DI-шов для грувера не удалён, `UsageCalculator()` продолжает работать, а порядок обязателен —
дефолт параметра может ссылаться только на объявленные раньше. `HarmonService` строит калькулятор
как `UsageCalculator(config.terminalApplications)`, иначе ключ парсился бы и терялся.

➕ Тест `UsageCalculatorTest.handsTheConfiguredTerminalListToTheApplicationGrouper` сверх чеклиста:
чеклист проверяет только `ApplicationGrouper`, а сломаться цепочка может ровно на участке
`UsageCalculator` → грувер. Тест сравнивает группировку при `terminalApplications = emptySet()`
и при дефолте на одних и тех же снимках.

➕ Тест `ConfigLoaderTest.keepsTheDefaultTerminalListWhenTheKeyIsAbsent` заодно проверяет строку
в `redactedDescription` — без этого новый ключ в выводе `check-config` ничем не покрыт.

### Task 14: Свести границу --sample-seconds к одному источнику

**Files:**
- Modify: `src/dev/yoda/harmon/cli/Cli.kt`
- Modify: `src/dev/yoda/harmon/config/Config.kt`
- Modify: `src/dev/yoda/harmon/runtime/HarmonService.kt`
- Modify: `test/CliParserTest.kt`

- [ ] написать тест: `once --sample-seconds 99999999999` отвергается парсером CLI
- [ ] написать тест: `once --sample-seconds 300` принимается, `301` — нет
- [ ] написать тест: сообщение об ошибке называет допустимый диапазон
- [ ] вынести `SAMPLE_SECONDS_RANGE = 1L..300L` в `config` и использовать в `ConfigLoader.validate`,
      `CliParser` и `HarmonService.sampleOnce` (сейчас там свой `require(sampleSeconds > 0)`)
- [ ] запустить `./kotlin test` — должно пройти до перехода к задаче 15

### Task 15: Аллоцировать массивы процессов по реальному числу PID

**Files:**
- Modify: `cinterop/harmon_native.def`
- Modify: `src/dev/yoda/harmon/monitor/DarwinSystemCollector.kt`
- Create: `test/ProcessCapacityTest.kt`

- [ ] написать тест на `internal fun processCapacityFor(count, capacity)`: типовое число процессов
      даёт `count + HEADROOM`, ноль и отрицательное — полную `capacity`, значение выше `capacity`
      обрезается до `capacity`, результат не меньше `MIN_PROCESS_CAPACITY`
- [ ] вынести расчёт ёмкости в `internal` функцию
- [ ] добавить `hm_count_processes()` (`proc_listallpids(NULL, 0)`) в нативный мост
- [ ] аллоцировать массивы процессов и issue по вычисленной ёмкости вместо фиксированных 16384/4096
- [ ] проверить вручную: `harmon diagnose` отдаёт то же число процессов, что и до правки
- [ ] запустить `./kotlin test` — должно пройти до перехода к задаче 16

### Task 16: Ограничить бюджет системных вызовов при обходе VM-регионов

**Files:**
- Modify: `cinterop/harmon_native.def`
- Modify: `src/dev/yoda/harmon/monitor/DarwinSystemCollector.kt`
- Modify: `docs/collection.md`

- [ ] добавить параметр `attribution_region_budget` в `hm_list_processes` и расходовать его в цикле атрибуции
- [ ] процесс, обход которого оборвался по лимиту или бюджету, не помечать
      `compressed_attribution_available` (заниженная сумма не должна выглядеть измеренной)
- [ ] процесс, до которого бюджет не дошёл, не помечать `compressed_attribution_attempted`
      (иначе `compressedAttributionFailureCount` считает несделанные попытки)
- [ ] снизить рабочий per-process лимит до 8192, оставив `HM_MAX_VM_REGIONS` предохранителем
- [ ] добавить конструкторный параметр `attributionRegionBudget` (дефолт 100000) в `DarwinSystemCollector`
- [ ] обновить описание счётчиков атрибуции в `docs/collection.md`
- [ ] проверить вручную: `DarwinSystemCollector(attributionRegionBudget = 0).capture()` даёт
      `compressedAttributionProcessCount == 0` и `compressedAttributionFailureCount == 0`
      при непустом списке процессов
- [ ] проверить вручную на машине с запущенным браузером: `time harmon diagnose` укладывается
      в единицы секунд
- [ ] запустить `./kotlin test` — должно пройти до перехода к задаче 17

### Task 17: Считать ранжирование приложений и процессов один раз

**Files:**
- Modify: `src/dev/yoda/harmon/report/ReportFormatter.kt`
- Modify: `src/dev/yoda/harmon/report/ReportJson.kt`
- Modify: `test/ReportFormatterTest.kt`
- Modify: `test/ReportJsonTest.kt`

- [ ] написать тест: при равных значениях метрики порядок элементов совпадает с
      `sortedByDescending { }.take(n)` (защита от разъезжания порядка)
- [ ] написать тест: вывод `text` и `json` на фикстуре не меняется после правки
      (эталон — строковая константа, снятая до изменения)
- [ ] вычислять ранжированные срезы один раз на отчёт и переиспользовать в обоих форматтерах
- [ ] сохранить `sortedByDescending` как механизм сортировки — самописная частичная выборка
      не пишется: выигрыш микросекундный, риск изменить порядок при равных значениях реальный
- [ ] запустить `./kotlin test` — должно пройти до перехода к задаче 18

### Task 18: Verify acceptance criteria

- [ ] пройти таблицу «находка → задача» из Context и отметить каждую закрытой
- [ ] проверить edge cases: пустой список алертов, отсутствие каналов уведомлений, отсутствие батареи
- [ ] прогнать полный набор тестов: `./kotlin test`
- [ ] собрать релиз: `./kotlin build --variant release` — предупреждений быть не должно
- [ ] прогнать локальный IPC smoke-тест из README (`collector --allow-unprivileged` + `once --config`)
- [ ] прогнать `harmon check-config` со старым конфигом, содержащим `alertCooldownSeconds`
- [ ] прогнать `harmon once --notify` и убедиться, что HTML-отчёт содержит все активные алерты,
      а пуш — только новые

### Task 19: [Final] Update documentation

- [ ] обновить README.md: секция уведомлений — событийная семантика, новые ключи конфига
- [ ] проверить, что в README и `docs/collection.md` не осталось упоминаний кулдауна
- [ ] обновить `docs/architecture.md`, если изменился поток алертов
- [ ] создать `CLAUDE.md`, если по ходу работы выявились неочевидные конвенции проекта
- [ ] переместить этот план в `docs/plans/completed/`

## Post-Completion

*Items requiring manual intervention or external systems - no checkboxes, informational only*

**Manual verification**:
- сутки подержать `harmon run` на реальной машине и убедиться, что уведомления приходят на переход
  через порог, а не пачками, и что повторов при колебаниях вокруг порога нет;
- проверить энергопотребление агента в Activity Monitor до и после задачи 9;
- проверить, что клик «Open report» по системному уведомлению открывает HTML (задачи 7 и 9
  затрагивают жизненный цикл run loop, юнит-тестами это не покрыто);
- проверить на машине с Chrome/Firefox, что сбор не выходит за таймаут сокета (30 с) после задачи 16.

**External system updates**:
- переустановить launchd-сервисы (`scripts/install.sh`) — бинарь агента и коллектора меняются оба,
  версии протокола совместимы, но обновлять их лучше вместе;
- потребители webhook получают новое поле `newAlertKeys` — поле аддитивное, ломать ничего не должно;
- у пользователей со старым конфигом `alertCooldownSeconds` появится предупреждение в stderr агента,
  видимое в логах launchd.
