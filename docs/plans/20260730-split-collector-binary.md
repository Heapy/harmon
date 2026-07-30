# Отдельный бинарь коллектора

## Overview

Разделить один Kotlin/Native executable, который сейчас одновременно служит root-коллектором,
пользовательским агентом и CLI, на два:

- `harmon` — пользовательский агент и интерактивный CLI;
- `harmon-collector` — минимальный root LaunchDaemon.

Это не только разделение команд. Финальная ссылка коллектора должна физически перестать содержать
код и динамические зависимости пользовательской половины:

- `libcurl` и весь HTTP-клиент;
- `libsqlite3`, sqliter-klib, SQLDelight runtime и сгенерированную схему;
- AppKit/Foundation и Notification Center;
- `notify/`, `history/`, `cli/` и парсер конфигурации с секретами.

Проверка `otool -L` — часть функциональной приёмки. Ветка исполнения, которая «не вызывает»
лишний код, недостаточна: цель — убрать этот код из root image и сократить его поверхность атаки.

Подкоманда `harmon collector` исчезает. Её место занимает отдельный процесс:

```shell
harmon-collector \
  --socket /var/run/harmon.collector.sock \
  --allowed-uid 501 \
  --allowed-gid 20
```

## Context (from discovery)

- **Проект**: Kotlin/Native, JetBrains Kotlin Toolchain 0.11.1, `macosArm64`, Kotlin 2.4.10,
  `allWarningsAsErrors: true`, `progressiveMode: true`; общий шаблон —
  `harmon.module-template.yaml`.
- **Версия**: 0.4.0. Ветка `feat/split-collector-binary` уже создана от текущего `main`;
  рабочее дерево перед началом чистое.
- **Текущий граф**: корневой `harmon` (`macos/app`), `nativebridge` (`kmp/lib`),
  `selftest` (`macos/app`), `plugins/sqldelight-gen` (`jvm/amper-plugin`).
- **Текущий мост**: `nativebridge/cinterop/harmon_native.def`, 1614 строк. В нём вместе лежат
  сокеты/framing, libproc/Mach/sysctl/IOKit и libcurl; `linkerOpts` равен
  `-framework IOKit -framework CoreFoundation -lcurl`.
- **История**: четыре `.sq`, `HistoryStore`, `HistoryRows`, `Retention`; плагин и
  `$libs.sqldelight.native.driver` сейчас включены в корневом модуле, там же находится явный
  `-lsqlite3`.
- **Тесты**: 34 Kotlin-файла в плоском корневом `test/`, семь C translation units в
  `test/native/`, отдельный `selftest`.
- **Установка**: `scripts/install.sh` собирает один release binary, кладёт его и в
  `Harmon.app`, и в `/Library/PrivilegedHelperTools/dev.yoda.harmon`; collector plist запускает
  тот же файл с первым аргументом `collector`.
- **Фактические швы**:
  - `CollectorSocket.kt` смешивает чистую политику accept/rejection, IPC client и IPC server;
  - `DarwinSystemCollector.kt` смешивает интерфейс/расчёт capacity и вызовы probe-моста;
  - `NotificationChannels.kt` смешивает интерфейс/dispatcher и AppKit/libcurl реализации;
  - `HarmonService` прямо импортирует `CollectorClient` и конкретный `HistoryStore`, а его
    значения параметров по умолчанию строят client и notification factory;
  - `SqlConversions.kt` не импортирует SQLDelight и содержит только арифметику `ULong → Long`.
- **Имена выходов**: у Toolchain 0.11.1 нет ключа `name:` для переименования модуля. Поэтому
  физический каталог приложения коллектора должен называться `harmon-collector/`; тогда выход —
  `build/tasks/_harmon-collector_linkMacosArm64*/harmon-collector.kexe`.

### Проверенные ограничения, на которых стоит решение

1. `linkerOpts` локального cinterop-модуля доходят до финальной ссылки зависимого app-модуля.
   Значит единый bridge нельзя оставить и надеяться на dead-code elimination.
2. Несколько `.def` одного модуля попадают к consumer все вместе, вместе со всеми `linkerOpts`.
   Значит нужны три физических bridge-модуля, а не три файла в одном.
3. Переносимого checked-in внешнего header для cinterop в Toolchain 0.11.1 нет. C остаётся inline
   после `---`; небольшие общие helpers дублируются.
4. KTC-5573 не мешает тестовому app-модулю транзитивно компилировать `src`-файл, который импортирует
   bridge, пока тест не импортирует binding и не исполняет его call site. Импорт binding из теста
   не компилируется; исполнение call site даёт `IrLinkageError`.
5. Владелец исправил первоначальный граф: SQLite нельзя оставлять в `core`. Для неё обязателен
   отдельный `history-sqlite`.

### Уточнение по фактическому состоянию C tests

`pure_test.c` сейчас не целиком probe-only: проверка
`pure.discard-http-response-consumes-everything` вызывает `hm_discard_http_response`, то есть
реальный callback из HTTP-моста. Дублировать callback в probe нельзя — тест тогда проверял бы не ту
реализацию. Эта одна проверка переезжает в новый `http_test.c` под префикс `http.`, а остальной
`pure_test.c` действительно становится probe-only. Это следует требованию резать по фактическим
вызовам, а не по старому списку файлов.

## Development Approach

- сначала сохранить согласованный дизайн в этом плане и соседнем плане Homebrew;
- менять граф инкрементально: bridge-модули → `core`/`history-sqlite` → отдельное приложение;
- после каждого компилируемого этапа выполнять `./kotlin build` до любого `./kotlin test`;
- коммитить законченные, собирающиеся швы отдельными коммитами с `refactor:`, `feat:`, `test:`,
  `docs:`;
- не реализовывать ничего из плана Homebrew/setup в рамках этой ветки;
- не менять протокол и `CollectorProtocol.VERSION`: разделение транспортно прозрачно;
- если фактическая линковка опровергнет проверенный граф — остановиться и зафиксировать ошибку,
  а не маскировать её новой архитектурой.

## Testing Strategy

- **module model**: `./kotlin show modules`, `./kotlin show tasks` и
  `./kotlin show settings -m <new-module>`; у каждого нового Kotlin-модуля источник
  `allWarningsAsErrors` должен быть виден как `harmon.module-template.yaml`;
- **unit tests**:
  - чистые тесты переезжают в `core/test`, где нет ни одного собственного `.def`;
  - SQLite tests переезжают в `history-sqlite/test` и по-прежнему работают на настоящем sqliter;
  - `DarwinCollectorLimitsTest` и новый parser test живут в `harmon-collector/test`;
  - root `test/` оставляет CLI/factory и внешние harness tests;
- **native C**: `scripts/test-native.sh` генерирует три header из трёх `.def`, затем собирает все
  suites одним harness binary; sanitized режим остаётся тем же;
- **binding**: `selftest` зависит только от `bridge-probe`, потому что только его типы и функции
  он называет;
- **e2e**: dev-коллектор запускается отдельным `harmon-collector.kexe`, `harmon diagnose` ходит к
  нему через `/tmp/harmon-dev.sock`;
- **link acceptance**:
  - `harmon-collector.kexe` не содержит `libcurl`, `libsqlite3`, AppKit или Foundation;
  - `harmon.kexe` содержит `libcurl` и `libsqlite3`, но не тянет IOKit через probe;
  - CoreFoundation и IOKit в коллекторе ожидаемы: это явные зависимости `bridge-probe`;
- известный sandbox-only отказ `sysctl vm.swapusage: Operation not permitted` отмечается отдельно
  и не считается регрессией; никакой другой красный тест не принимается.

## Progress Tracking

- mark completed items with `[x]` immediately when done
- add newly discovered tasks with ➕ prefix
- document issues/blockers with ⚠️ prefix
- update plan if implementation deviates from original scope
- keep plan in sync with actual work done

## Solution Overview

Итоговый граф:

```text
                         ┌──────────────┐
                         │     core     │
                         │ pure Kotlin  │
                         └──────┬───────┘
                                │
          ┌─────────────────────┼────────────────────────┐
          │                     │                        │
  ┌───────▼────────┐    ┌───────▼────────┐      ┌────────▼────────┐
  │ history-sqlite │    │     harmon     │      │ harmon-collector│
  │ SQLDelight     │    │ agent + CLI    │      │ root daemon     │
  └───────┬────────┘    └──┬──────────┬──┘      └──┬──────────┬──┘
          │                │          │            │          │
       sqliter       bridge-ipc  bridge-http  bridge-ipc  bridge-probe
       sqlite3                       curl                    IOKit/CF
```

`core` содержит модель, versioned protocol, расчёты, правила, отчёты, конфигурацию и
`HarmonService`, но ни одного `.def` и ни одного SQLDelight-типа. Он задаёт четыре чистых шва:

- `SystemCollector`;
- `NotificationChannel`/`NotificationDispatcher`;
- `History`;
- чистую политику accept/rejection.

Конкретные реализации собираются только в composition roots двух приложений.

## Technical Details

### Модули и зависимости

| Модуль | Product | Содержимое | Зависимости финальной ссылки |
| --- | --- | --- | --- |
| `core/` | `kmp/lib`, `macosArm64` | model, protocol, analysis, config, report, util, runtime, чистые интерфейсы | kotlinx.serialization; без `.def` |
| `bridge-ipc/` | `kmp/lib` | `harmon_ipc.def`, package `dev.yoda.harmon.nativebridge.ipc` | без `linkerOpts` |
| `bridge-probe/` | `kmp/lib` | `harmon_probe.def`, package `dev.yoda.harmon.nativebridge.probe` | IOKit, CoreFoundation |
| `bridge-http/` | `kmp/lib` | `harmon_http.def`, package `dev.yoda.harmon.nativebridge.http` | libcurl |
| `history-sqlite/` | `kmp/lib` | `.sq`, codegen, store/rows/retention | core, native-driver, sqlite3 |
| корневой `harmon` | `macos/app` | main, CLI, IPC client, notify implementations, HTML store | core, history-sqlite, bridge-ipc, bridge-http |
| `harmon-collector/` | `macos/app` | main/parser, IPC server, Darwin collector | core, bridge-ipc, bridge-probe |
| `selftest/` | `macos/app` | Kotlin binding checks | bridge-probe |

Все семь Kotlin-модулей применяют `harmon.module-template.yaml` относительным путём от своего
`module.yaml`. SQLDelight plugin остаётся зарегистрирован в `project.yaml`, но включается только в
`history-sqlite/module.yaml`.

### Разрез Kotlin source

Целиком в `core/src/dev/yoda/harmon/`:

- `model/Models.kt`;
- `ipc/CollectorProtocol.kt`;
- `monitor/UsageCalculator.kt`;
- `analysis/*.kt`;
- `report/*.kt`;
- `config/Config.kt`;
- `util/*.kt`;
- `runtime/HarmonService.kt`;
- чистая `history/History.kt` и арифметическая `history/SqlConversions.kt`.

Файлы, которые режутся по вызову bridge:

| Сейчас | `core` | Конкретный app |
| --- | --- | --- |
| `ipc/CollectorSocket.kt` | `RejectionLog`, `AcceptDecision`, `acceptDecision`, `consecutiveFailuresAfter`, лимиты | `CollectorClient` → root; `CollectorServer` → collector |
| `monitor/DarwinSystemCollector.kt` | `SystemCollector`, `CollectionException`, `processCapacityFor`, capacity constants | `DarwinSystemCollector` → collector |
| `notify/NotificationChannels.kt` | `NotificationChannel`, `DeliverySummary`, `NotificationDispatcher` | system/webhook/Telegram, `NativeHttpClient`, factory `from()` → root |

Целиком в root `harmon`:

- `cli/Cli.kt` без `Command.Collector`;
- `notify/HtmlReportStore.kt`;
- конкретные notification channels;
- `ipc/CollectorClient.kt`;
- `src/main.kt`.

Целиком в `harmon-collector`:

- `monitor/DarwinSystemCollector.kt`;
- `ipc/CollectorServer.kt`;
- `collector/CollectorCli.kt`;
- `main.kt`.

### История и граница SQLite

В `core` появляется `History` — минимальный контракт, который использует `HarmonService`:

```kotlin
interface History {
    fun record(
        report: MonitoringReport,
        deliveries: List<DeliveryResult> = emptyList(),
        alertState: AlertStateSnapshot? = null,
    )

    fun restorableAlertState(now: Instant = Clock.System.now()): AlertStateSnapshot?
}
```

Конкретный `history-sqlite`:

- `HistoryStore : History`;
- `HistoryRows.kt`, `Retention.kt`;
- `history-sqlite/sqldelight/dev/yoda/harmon/db/*.sq`;
- `plugins: { sqldelight-gen: enabled }`;
- `$libs.sqldelight.native.driver`;
- `freeCompilerArgs: [-linker-option, -lsqlite3]`.

`SqlConversions.kt` остаётся в core: у него нет импортов SQLDelight, sqliter или generated db.
`HistoryStore` сохраняет своё деградирующее поведение. Меняется только тип, который видит service.

### Явный composition root

Из `HarmonService` убираются только дефолты, которые строят тяжёлые реализации:

- `collector = CollectorClient(...)`;
- `notifications = lazy { NotificationDispatcher.from(...) }`.

Дефолты чистых компонентов (`UsageCalculator`, `AlertAnalyzer`, `ApplicationGrouper`) и
опциональной `history = null` сохраняются. Корневой `main.kt` явно соединяет:

- `CollectorClient(config.collectorSocket)`;
- lazy `NotificationDispatcher.from(config.notifications)`;
- `HistoryStore.openOrNull(...)` только для `run`.

Collector `main.kt` явно соединяет `CollectorServer` с `DarwinSystemCollector`.
Изменение конвенции DI документируется в `CLAUDE.md`.

### Три inline-C bridge

`harmon_native.def` удаляется после переноса функций:

- `harmon_ipc.def`: Unix server/connect/accept, framing, descriptor/remove, socket options,
  `HM_MAX_JSON_FRAME_SIZE`, `hm_sleep_millis`, `hm_free`;
- `harmon_probe.def`: все structs и constants сбора, включая `HM_MAX_PROCESS_ARGS`,
  libproc/Mach/sysctl/IOKit функции, `hm_monotonic_time_ns`, `hm_uint32_counter`,
  `hm_saturating_*`, `hm_mach_time_to_ns`, `hm_free`;
- `harmon_http.def`: `HMHttpResult`, curl init/cleanup/post и response callback.

У каждого свой include guard и `headerFilter`. `hm_free` дублируется в IPC и probe как inline
helper; разные `package =` обязательны, чтобы приложение с двумя bridge не получило конфликт
Kotlin declarations.

### Раскладка тестов

- `core/test`: чистые analyzer/state/grouper/protocol/config/report/runtime/usage tests,
  `AcceptDecisionTest`, `RejectionLogTest`, `ProcessCapacityTest`, `SqlConversionsTest`;
- `history-sqlite/test`: все `History*`, `RetentionTest` и SQLite round-trip части
  `AlertStateSnapshotTest`;
- root `test`: `CliParserTest` без collector case, notification factory tests,
  `NativeCTest`, `NativeHarness*`, `SelftestBridgeTest`;
- `harmon-collector/test`: `DarwinCollectorLimitsTest`, новый `CollectorCliParserTest`.

Общий старый `TestFixtures.kt` делится по модульной границе. Test source зависимого модуля не
экспортируется, поэтому небольшие model builders для history tests дублируются в test-коде, а не
выносятся в production-модуль.

`selftest` staleness guard следит за `bridge-probe/cinterop/harmon_probe.def`,
`bridge-probe/module.yaml`, `selftest/src`, `selftest/module.yaml` и общим template.

### Native harness

`scripts/test-native.sh` создаёт:

- `build/native-test/harmon_ipc.h`;
- `build/native-test/harmon_probe.h`;
- `build/native-test/harmon_http.h`.

`framing_test.c` и `socket_test.c` включают IPC header. `processes_test.c`,
`attribution_test.c`, `snapshot_test.c`, `pure_test.c` включают probe header.
HTTP callback check переносится в `http_test.c`, который включает HTTP header. Link options
считываются из probe и HTTP `.def`; отсутствие `linkerOpts` у IPC нормально.

### Установка до Homebrew

Текущий `scripts/install.sh` остаётся source installer на переходный период и:

- один раз вызывает release build, который собирает оба app-модуля;
- копирует `harmon.kexe` в `Harmon.app`;
- копирует только `harmon-collector.kexe` в
  `/Library/PrivilegedHelperTools/harmon-collector`;
- генерирует collector plist без аргумента `collector`;
- удаляет прежний `/Library/PrivilegedHelperTools/dev.yoda.harmon` после bootout.

`scripts/uninstall.sh` удаляет новый helper и старый legacy path. План Homebrew заменит этот
imperative flow на `harmon setup`, но это отдельная работа.

## What Goes Where

- **Implementation Steps** (`[ ]`): весь рефакторинг, тесты, документация и link acceptance этой
  ветки.
- **Post-Completion** (без чекбоксов): наблюдение за установленной парой и будущая реализация
  Homebrew/setup по соседнему плану.

## Implementation Steps

### Task 1: Зафиксировать новый module graph

**Files:**
- Modify: `project.yaml`
- Modify: `module.yaml`
- Create: `core/module.yaml`
- Create: `bridge-{ipc,probe,http}/module.yaml`
- Create: `history-sqlite/module.yaml`
- Create: `harmon-collector/module.yaml`
- Modify: `selftest/module.yaml`

- [ ] зарегистрировать все новые модули и удалить `./nativebridge`
- [ ] оставить SQLDelight plugin зарегистрированным project-wide, включить его только в history
- [ ] перенести native-driver и `-lsqlite3` из root module в `history-sqlite`
- [ ] настроить зависимости двух app-модулей ровно по графу Solution Overview
- [ ] применить `harmon.module-template.yaml` в каждом новом модуле
- [ ] проверить `./kotlin show modules`, tasks и effective settings всех новых модулей

### Task 2: Разделить C bridge

**Files:**
- Delete: `nativebridge/cinterop/harmon_native.def`
- Create: `bridge-ipc/cinterop/harmon_ipc.def`
- Create: `bridge-probe/cinterop/harmon_probe.def`
- Create: `bridge-http/cinterop/harmon_http.def`
- Modify: Kotlin imports в IPC, collector, notification и selftest source

- [ ] перенести текущий C body по фактическому call graph, включая `HM_MAX_PROCESS_ARGS` в probe
- [ ] оставить у IPC пустой набор linker options, у probe только IOKit/CoreFoundation, у HTTP
  только `-lcurl`
- [ ] задать три разных packages и guards; продублировать только inline helpers, которые реально
  нужны двум мостам
- [ ] обновить импорты на `.ipc`, `.probe`, `.http`
- [ ] собрать проект до перехода к тестам

### Task 3: Адаптировать native harness и selftest

**Files:**
- Modify: `scripts/test-native.sh`
- Modify: `test/native/{framing,socket,processes,attribution,snapshot,pure}_test.c`
- Create: `test/native/http_test.c`
- Modify: `test/native/{main.c,harness.h}`
- Modify: `test/NativeCTest.kt`
- Modify: `test/{NativeHarness.kt,NativeHarnessTest.kt,SelftestBridgeTest.kt}`
- Modify: `selftest/src/main.kt`

- [ ] генерировать три header и собирать C harness с linkerOpts двух непустых bridge
- [ ] вынести реальную проверку `hm_discard_http_response` в suite `http.`
- [ ] обновить suite map и полный ожидаемый список check names
- [ ] перевести selftest только на `bridge-probe`
- [ ] обновить staleness guard и его unit tests
- [ ] выполнить обычный и sanitized native harness

### Task 4: Выделить core без тяжёлых реализаций

**Files:**
- Move: чистые каталоги из `src/dev/yoda/harmon/` → `core/src/dev/yoda/harmon/`
- Create: `core/src/dev/yoda/harmon/{ipc/CollectorDecisions.kt,monitor/SystemCollector.kt}`
- Create: `core/src/dev/yoda/harmon/notify/NotificationDispatcher.kt`
- Create: `core/src/dev/yoda/harmon/history/History.kt`
- Modify: `core/src/dev/yoda/harmon/runtime/HarmonService.kt`
- Move/split: чистые root tests → `core/test/`

- [ ] разрезать три смешанных Kotlin-файла ровно по native call sites
- [ ] ввести чистый `History` и переключить на него `HarmonService`
- [ ] убрать bridge-touching defaults из `HarmonService`, остальные defaults сохранить
- [ ] разделить `NotificationDispatcherTest`: чистая политика в core, factory cases в root
- [ ] перенести/адаптировать service tests на fake `History`
- [ ] собрать и прогнать core tests без собственного cinterop

### Task 5: Выделить history-sqlite

**Files:**
- Move: `sqldelight/` → `history-sqlite/sqldelight/`
- Move: `HistoryStore.kt`, `HistoryRows.kt`, `Retention.kt` → `history-sqlite/src/.../history/`
- Keep in core: `history/SqlConversions.kt`
- Move/split: history tests → `history-sqlite/test/`
- Create: `history-sqlite/test/TestFixtures.kt`

- [ ] реализовать `HistoryStore : History` без изменения деградации/ретеншна
- [ ] убедиться, что generated db и SQLDelight imports встречаются только в history-sqlite
- [ ] разделить `AlertStateSnapshotTest` на pure и database round-trip части
- [ ] сохранить production-driver tests и SQLite helper fixtures в history module
- [ ] проверить, что core compile/link не получает `-lsqlite3`
- [ ] выполнить build до history tests, затем полный набор тестов модуля

### Task 6: Разделить client/server и добавить harmon-collector

**Files:**
- Create: root `src/dev/yoda/harmon/ipc/CollectorClient.kt`
- Create: `harmon-collector/src/dev/yoda/harmon/ipc/CollectorServer.kt`
- Create: `harmon-collector/src/dev/yoda/harmon/monitor/DarwinSystemCollector.kt`
- Create: `harmon-collector/src/dev/yoda/harmon/collector/CollectorCli.kt`
- Create: `harmon-collector/src/main.kt`
- Create: `harmon-collector/test/CollectorCliParserTest.kt`
- Move: `test/DarwinCollectorLimitsTest.kt` → `harmon-collector/test/`
- Modify: root `src/dev/yoda/harmon/cli/Cli.kt`, `test/CliParserTest.kt`

- [ ] удалить `Command.Collector`, parser branch, root check и server startup из `harmon`
- [ ] перенести прежний collector parser case в test нового parser
- [ ] сохранить `--allow-unprivileged`, абсолютный socket path и обязательные uid/gid
- [ ] явно собрать `CollectorServer(DarwinSystemCollector())` в collector main
- [ ] убедиться, что имя выхода — `harmon-collector.kexe`

### Task 7: Сделать root composition явным

**Files:**
- Modify: `src/main.kt`
- Modify: `src/dev/yoda/harmon/cli/Cli.kt`
- Create/Modify: root notification implementation/factory files
- Modify: root notification factory tests

- [ ] оставить в CLI orchestration, но передавать ему service/history factories из `main.kt`
- [ ] собирать `CollectorClient`, lazy notification dispatcher и `HistoryStore` только в root main
- [ ] открывать history только для `run`, как раньше
- [ ] сохранить поведение `once`, `diagnose`, `check-config`, `test-notifications`
- [ ] проверить help: в нём больше нет `harmon collector`

### Task 8: Обновить source installer и launchd

**Files:**
- Modify: `scripts/install.sh`
- Modify: `scripts/uninstall.sh`
- Modify: `launchd/dev.yoda.harmon.collector.plist.template`

- [ ] проверять наличие обоих release `.kexe`
- [ ] устанавливать разные файлы в app bundle и PrivilegedHelperTools
- [ ] убрать аргумент `collector` из daemon plist
- [ ] мигрировать/удалять legacy helper path безопасно после `bootout`
- [ ] сохранить uid/gid/socket и текущую двухдоменную последовательность launchctl
- [ ] прогнать `plutil -lint` над обоими сгенерированными plist

### Task 9: Обновить наблюдаемую документацию

**Files:**
- Modify: `README.md`
- Modify: `CLAUDE.md`
- Modify: `docs/architecture.md`
- Modify: `docs/native-testing.md`
- Modify: `docs/collection.md`
- Modify: `docs/history.md`

- [ ] описать два release outputs, две команды и новый dev-рецепт
- [ ] заменить устаревшие пути nativebridge и generated SQLDelight output
- [ ] зафиксировать явный composition root и новую DI convention в `CLAUDE.md`
- [ ] описать три headers, suite mapping и staleness guard в native testing
- [ ] удалить из architecture будущий hardening step: он стал текущей архитектурой
- [ ] обновить install paths и project tree

### Task 10: Verify acceptance criteria

- [ ] выполнить `./kotlin build`
- [ ] выполнить `./kotlin test`
- [ ] выполнить `scripts/test-native.sh`
- [ ] выполнить `otool -L` для debug `harmon.kexe`
- [ ] выполнить `otool -L` для debug `harmon-collector.kexe`
- [ ] доказать отсутствия curl/sqlite/AppKit/Foundation в collector и IOKit в harmon
- [ ] отдельно записать sandbox-only `vm.swapusage` отказ, если он воспроизвёлся
- [ ] проверить `git diff --check`, историю логических коммитов и чистое рабочее дерево

## Post-Completion

*Требует наблюдения или отдельного плана — без чекбоксов.*

**Наблюдение после реальной установки:**

- launchd действительно запускает новый helper после обновления поверх старой однобинарной версии;
- root collector остаётся стабилен при длительной работе и не меняет protocol payload;
- размер и набор зависимостей collector image не растут обратно при будущих фичах.

**Следующая работа:**

- реализовать `docs/plans/20260730-brew-install-setup.md`;
- после первого release двух бинарей проверить upgrade старой установки и затем убрать legacy
  cleanup из source installer, когда переходное окно закончится.
