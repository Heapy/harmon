# Parallel remediation of the Live sampler review

## Цель и границы

Исправить подтверждённые дефекты review диапазона `e99585f..4e88681`, не
меняя продуктовую семантику demand-driven Live sampler:

- collector остаётся request-driven, а capture выполняются строго
  последовательно;
- только видимая вкладка Live продлевает lease; hidden, Snapshot и закрытая
  вкладка — нет;
- каждая новая lease-generation начинает новую baseline-сессию с `FULL`, даже
  если предыдущая generation закончилась менее 30 секунд назад;
- внутри одной непрерывной generation старты `FULL` разделены минимум
  30 секундами, deadline считается от старта предыдущего `FULL`;
- полный текстовый отчёт пересобирается на каждом успешном fast-снимке;
- scheduled monitoring, `once`, notifications и history используют только
  `FULL` и не зависят от Live UI;
- protocol остаётся v3, Web UI schema — v2.

Работа идёт в изолированных Git worktrees. Worker меняет только закреплённые
за ним файлы, делает собственный commit и передаёт SHA coordinator. Только
coordinator владеет integration-веткой, общими документами, glue, финальным
review и push.

## Триаж findings

### Исправить в этом цикле

1. Абсолютные collector frame deadlines вместо таймаута каждого отдельного
   `recv`/`send`, плюс маленький лимит request frame.
2. Корректное направление protocol diagnostics и transport-level regression
   tests для malformed, truncated и slow-drip frames.
3. Отсутствие hot loop/catch-up после capture, который длился дольше sampling
   period.
4. Синхронно наблюдаемый `WARMING` на первом `watch=1` после idle и атомарная
   защита от результата старой generation.
5. Bounded recovery при ошибке JSON encoding: сохранить последний успешно
   закодированный JSON, продолжить cadence и восстановиться на следующей
   успешной публикации. Не создавать второй `STALE`/`staleSince` поверх
   session-owned capture state.
6. Единая временная семантика global attribution counters из последнего
   `FULL`, явно отделённая от текущего per-application coverage.
7. Неблокирующий HTTP accept lifecycle: bounded workers, self-pipe wakeup,
   generation identity, проверенные socket options и drain без fd-reuse race.
8. Строгая проверка `Host` и удаление token из HTTP request target,
   отображаемого browser URL и polling URLs через fragment bootstrap +
   `sessionStorage`. Persistent browser history не заявляется очищенной.
9. Сохранение сортировки PID/Process при смене preset/columns.
10. Продвижение единицы после округления `formatBytes`, отсутствующий OOM
    result в native test и устаревший комментарий конфигурации.
11. Реальные integration tests sampler и collector transport, а не только
    тесты policy/helper классов.
12. Перенос bridge-backed `LiveUiEndpointStore` и token generation из default
    construction `LiveUiRuntime`/`LiveUiLauncher` в `src/main.kt`.
13. Воспроизводимый performance harness, который отделяет измерения collector
    от детерминированных гарантий sampler и не добавляет production logging.

### Сохранить как намеренное поведение

- Два `FULL` из разных lease-generations могут начаться ближе чем через
  30 секунд. Ограничение действует только внутри непрерывной generation.
- Deadline следующего `FULL` считается от фактического старта предыдущего
  `FULL`, не от окончания.
- Полный text report обновляется на каждом fast sample, даже если `<details>`
  свёрнут.
- Global `measured/failed` — одна датированная пара последнего `FULL`.
  Per-application count — число текущих members, для которых нашлась cached
  attribution. Эти числа намеренно не обязаны суммироваться друг в друга.

Эти решения закрепляются тестами и authoritative docs, чтобы следующий review
не «исправил» их обратно.

### Отложить до профилирования или отдельного design review

- сокращение промежуточных map/copy в process tree и JSON normalization;
- ленивую генерацию полного text report;
- устранение копии последней payload, если memory profile не покажет проблему;
- protocol/schema bump ради более детального per-process attribution attempt;
- signal-based cleanup endpoint-файла: корректный async-signal-safe lifecycle
  требует отдельного дизайна.

## Общие правила и API-контракты

Перед началом coordinator:

1. получает финальный `APPROVE` для этого плана, коммитит reviewed plan и
   фиксирует SHA как `<BASE_SHA>`; pre-plan база —
   `1bfc0d95cce8325739228dc5c42d811371ffdeea`;
2. проверяет чистый status, отсутствие целевых веток/путей и не удаляет уже
   существующие worktrees;
3. создаёт integration worktree и Wave 1 worktrees от одного `<BASE_SHA>`;
4. ограничивает одновременно работающие тяжёлые build/test процессы двумя,
   чтобы cache contention не искажал проверки.

Общие правила workers:

- сначала прочитать `CLAUDE.md` и authoritative docs своей подсистемы;
- не менять чужие файлы, общие docs или `core/test/TestFixtures.kt`;
- не менять cross-worker public signatures, кроме перечисленных в этом плане:
  deadline-aware IPC helpers, sampler test seams/lifecycle, attribution cache
  semantics, явные аргументы `LiveUiRuntime`, HTTP auth/lifecycle helpers;
- если нужен иной чужой контракт, остановиться и передать coordinator
  минимальный proposal, не делать удобную правку за владельца;
- все непосредственно тестируемые production helpers делать public только там,
  где этого требует KTC-5573;
- private top-level test classifiers получать префикс своего агента, потому
  что package-less tests модуля делят один namespace;
- не создавать общий cross-agent fixture: Agent 1 и Agent 2 строят минимальные
  `RawSystemSnapshot`/transport fixtures в собственных test-файлах с префиксами
  `CollectorIpc*` и `SamplerRuntime*` соответственно;
- перед тестами выполнять `./kotlin build`;
- не запускать setup/uninstall, не менять установленный launchd service и не
  использовать root без отдельного разрешения;
- сделать одну логическую серию commits, сообщить SHA, tests и remaining risks;
  после handoff не amend/rebase;
- перед handoff обязательны `git diff --check` и чистый status.

Worker branches не редактируют `README.md`, `docs/architecture.md`,
`docs/collection.md` и `docs/native-testing.md`. Plan, implementation и
authoritative docs составляют одну integration-серию — это считается тем же
изменением в смысле требования `CLAUDE.md`.

## Worktree topology

### Wave 1 — независимые исправления

| Роль | Worktree | Ветка | Test prefix |
|---|---|---|---|
| Coordinator | `/private/tmp/harmon-fix-integration` | `fix/live-review-integration` | — |
| Agent 1 — collector/IPC | `/private/tmp/harmon-fix-collector` | `fix/live-collector-deadlines` | `CollectorIpc*` |
| Agent 2 — sampler runtime | `/private/tmp/harmon-fix-sampler` | `fix/live-sampler-runtime` | `SamplerRuntime*` |
| Agent 3 — attribution | `/private/tmp/harmon-fix-attribution` | `fix/live-attribution-consistency` | `Attribution*` |
| Agent 4 — UI semantics/correctness | `/private/tmp/harmon-fix-ui` | `fix/live-ui-correctness` | `ProcessUi*` |
| Agent 5 — low-risk polish | `/private/tmp/harmon-fix-polish` | `fix/live-review-polish` | — |

Каждый worktree создаётся командой:

```shell
git worktree add -b <branch> <absolute-worktree-path> <BASE_SHA>
```

Wave 1 содержит пять логически параллельных tracks. Если доступных agent slots
меньше, Agent 5 ставится в очередь; file ownership и общая база не меняются.

### Agent 1 — bounded collector IPC

Эксклюзивное владение:

- `bridge-ipc/cinterop/harmon_ipc.def`;
- `harmon-collector/src/dev/yoda/harmon/ipc/CollectorServer.kt`;
- `src/dev/yoda/harmon/ipc/CollectorClient.kt`;
- `harmon-collector/test/CollectorRequestHandlerTest.kt`;
- `core/src/dev/yoda/harmon/ipc/CollectorProtocol.kt`;
- `core/test/CollectorProtocolTest.kt`;
- `test/native/framing_test.c`;
- `test/native/socket_test.c`;
- `test/NativeCTest.kt` и `test/NativeHarness.kt`;
- новый `test/CollectorIpcIntegrationTest.kt`.

Реализация:

1. Добавить deadline-aware send/receive helpers для целого frame. Один
   monotonic deadline охватывает length header и payload; `EINTR` пересчитывает
   только остаток. Slow drip не перезапускает deadline.
2. Использовать `poll` и неблокирующие `send`/`recv` с saturating arithmetic,
   корректным округлением миллисекунд и `ETIMEDOUT`. Существующие API оставить
   wrappers с абсолютным общим deadline 30 секунд.
3. Ограничить collector request `PROBE|CAPTURE` до 4 KiB. Интервал от отправки
   `HELLO` до полного request frame получает единый deadline 5 секунд: он всё
   ещё краток для локального IPC, но не создаёт flakes под load.
4. Client snapshot receive deadline 30 секунд включает ожидание collector
   compute и transfer. Это намеренно строже прежнего per-syscall поведения;
   обычный `FULL` должен укладываться с большим запасом. Server snapshot send
   также имеет единый 30-секундный transfer deadline.
   `CollectorClient.kt` принадлежит Agent 1: по возможности он остаётся без
   изменений, потому что deadline реализуется bridge-wrapper, но никакая
   необходимая client-side адаптация не блокируется ownership-правилом.
   Если новый client parameter всё же необходим, он получает совместимый
   default, чтобы Wave 1 `CollectorClient(config.collectorSocket)` в
   `src/main.kt` не требовал параллельной правки файла Agent 2.
5. Сохранить уже существующее поведение: malformed, oversized или timed-out
   connection закрывается отдельно, после чего accept-loop продолжает работу.
   Зафиксировать это тестом; не добавлять protocol `ERROR` и не поднимать v4.
6. Оставить collector строго последовательным. Worker pool запрещён: он
   допустил бы overlap capture и умножил дорогой VM walk.
7. Исправить только направление diagnostics: входящий request — `Collector
   received invalid …`, ответ — `Collector returned invalid …`; specific
   version mismatch сохраняет приоритет.

Тесты и acceptance:

- drip-fed header/payload получает `ETIMEDOUT` около общего deadline;
- slow reader не растягивает send бесконечно; split frame до deadline проходит;
- malformed, oversized и unknown profile не вызывают capture;
- `PROBE` вызывает ноль capture, оба профиля сохраняют applied-profile;
- real-binary test использует `posix_spawn`, затем `kill` + `waitpid` в
  `finally`, а не shell/`popen`: первый client читает `HELLO` и молчит, второй
  probe завершается после bounded handshake, collector остаётся жив;
- если debug binary отсутствует или старее input, test выдаёт явное
  `run ./kotlin build`, а не неясный transport failure;
- timing integration повторяется 10–20 раз без flakes;
- private helpers называются только `CollectorIpc*`.

Проверка:

```shell
./kotlin build
scripts/test-native.sh framing.
scripts/test-native.sh socket.
scripts/test-native.sh --sanitize
./kotlin test -m core
./kotlin test -m harmon-collector
./kotlin test -m harmon
```

Collector accepted sockets уже получают проверенные `SO_NOSIGPIPE`, timeouts и
`FD_CLOEXEC`; finding относится к HTTP. Существующие assertions в
`test/native/socket_test.c` сохраняются. Если nonblocking/poll реализация
осознанно меняет сам контракт socket options, Agent 1 обновляет этот test и
передаёт coordinator точный delta для `docs/native-testing.md`, а не оставляет
устаревшую проверку.

### Agent 2 — sampler runtime and publication state

Эксклюзивное владение:

- `src/dev/yoda/harmon/web/LiveUiRuntime.kt`;
- `src/main.kt` только для явной app-specific construction;
- новый `test/LiveUiSamplerTest.kt`.

Реализация:

1. Ввести отдельно injectable monotonic clock для lease/cadence и wall clock
   `() -> Instant` для `generatedAt`; wall clock передавать в каждую новую
   `LiveSamplingSession`.
2. Вынести cadence arithmetic в детерминированный public helper. Следующий
   deadline — первый tick, строго следующий за фактическим окончанием capture,
   с anchor в старте capture:

   - end `start + 0.4 s` → `start + 1 s`;
   - end `start + 1.2 s` → `start + 2 s`;
   - end ровно `start + 2 s` → `start + 3 s`.

   Один helper используется после success и во всех capture/error branches.
   Missed slots пропускаются; очередь и immediate catch-up не создаются.
3. Сделать idle activation зарезервированной атомарной операцией:

   - под `lifecycleLock` renew определяет новую generation, записывает
     `activationPendingGeneration` и snapshot последней успешно опубликованной
     пары payload+JSON;
   - lock отпускается, `WARMING` строится и кодируется без удержания lifecycle;
   - после повторного захвата lock проверяются lifecycle state, та же active
     generation и pending reservation;
   - `state.update`, обновление last-published pair и отметка
     `warmingPublishedGeneration` выполняются одним участком; pending
     очищается, waiters пробуждаются;
   - capture loop ждёт pending activation и не публикует второй `WARMING`, если
     generation уже отмечена published;
   - concurrent renew, увидевший pending, ждёт его завершения и сам ничего не
     кодирует; это condition wait имеет monotonic timeout 2 секунды, после
     которого waiter освобождает HTTP worker и пишет bounded/coalesced
     diagnostic, не очищая чужую reservation; renew уже активной
     опубликованной generation — no-op кроме lease;
   - любая encoding exception очищает reservation и будит waiters в `finally`,
     оставляя последний успешный JSON, поэтому failure не создаёт deadlock.

   На нормальном пути первый activating `watch=1` не возвращается до публикации
   `WARMING`; старый `READY` не выдаётся как свежий снимок новой generation.
4. Единственный lock order: `lifecycleLock` → lock внутри `LiveUiState`.
   `LiveUiServer.onWatch` вызывается вне lock `LiveUiState` и остаётся таким.
5. Для любого capture JSON можно кодировать без lifecycle lock, но повторная
   generation/lifecycle check, `state.update` и last-published update атомарны
   под `lifecycleLock`. Result после expiry, stop или новой generation
   отбрасывается.
6. Сделать encoder injectable. При encoding failure:

   - сохранить последний успешно закодированный JSON и payload;
   - логировать ошибку coalesced не чаще одного раза на generation до recovery;
   - продолжить обычный cadence без отдельного retry loop;
   - следующий успешный encode публикуется и сбрасывает coalescing state.

   Sampler не строит дополнительный `STALE`: capture/calculation stale state и
   единый `staleSince` остаются ответственностью `LiveSamplingSession`.
7. Добавить default-no-op diagnostics callback только как test seam. Он
   сообщает profile, start/end, missed-slot count и in-flight count; production
   не пишет ежесекундный лог.
8. Сделать sampler single-shot: `NEW → RUNNING → STOPPED`, повторный `start()`
   после `stop()` бросает ошибку. `stop()` инвалидирует generation, будит
   ожидания и ждёт drain loop/in-flight capture не более 35 секунд: 30 секунд
   collector reply deadline плюс 5 секунд scheduling slack. При превышении он
   выдаёт bounded diagnostic и возвращает управление в состоянии `STOPPED`;
   eventual old result всё равно отвергается и экземпляр нельзя перезапустить.
   `LiveUiRuntime` следует тому же single-shot contract и не переиспользует
   остановленный sampler.
9. Удалить default construction token и `LiveUiEndpointStore` как из
   `LiveUiRuntime`, так и из `production`. `src/main.kt` явно создаёт
   `generateLiveUiToken()` и `LiveUiEndpointStore()` и передаёт оба аргумента.
10. Новая generation всегда создаёт новую `LiveSamplingSession`; attribution
    cache и FULL deadline через idle не переносятся.

Детерминированные tests настоящего `LiveUiSampler`:

- без renew capture отсутствует;
- первый renew после idle синхронно публикует `WARMING`; concurrent renew ждёт
  ту же reservation, повторный active renew не сбрасывает `READY`;
- normal profiles: `FULL`, затем `LIVE_FAST`; reactivation снова начинает
  `FULL`, даже если прошло меньше 30 секунд;
- overrun на success и failure пропускает просроченные slots, никогда не даёт
  back-to-back capture, diagnostics показывает `maxInFlight == 1`;
- old result отвергается после expiry/new generation и после `stop()`;
- injected encode failure сохраняет прежний JSON, не создаёт второй `STALE`,
  следующий encode восстанавливает публикацию;
- `start → stop → start` одного экземпляра отклоняется, stop drain bounded;
- test с capture, не завершившимся к synthetic stop deadline, подтверждает
  возврат управления, `STOPPED` и запрет eventual publication;
- все waits имеют monotonic timeout, stop вызывается в `finally`, private
  helpers и локальные snapshot fixtures имеют префикс `SamplerRuntime*`.

Проверка: `./kotlin build`, `./kotlin test -m harmon`.

### Agent 3 — temporally consistent attribution

Эксклюзивное владение:

- `core/src/dev/yoda/harmon/runtime/LiveSamplingSession.kt`;
- `core/test/LiveSamplingSessionTest.kt`;
- `core/src/dev/yoda/harmon/analysis/ApplicationGrouper.kt`;
- `core/test/ApplicationGrouperTest.kt`;
- `core/src/dev/yoda/harmon/report/ReportFormatter.kt`;
- `core/test/ReportFormatterTest.kt`;
- при необходимости для label/contract:
  `core/src/dev/yoda/harmon/report/WebUiPayload.kt` и
  `core/test/WebUiPayloadTest.kt`.

Реализация:

1. Кэшировать с `FULL` как одну датированную state: attribution map,
   `compressedAttributionProcessCount`,
   `compressedAttributionFailureCount` и capture timestamp.
2. На FAST накладывать map только по `(pid, startedAt)`. Новый или reused PID не
   получает старое значение и делает соответствующий subtree total partial.
3. Global aggregate pair переносить целиком из того же `FULL`, не пересчитывать
   только measured по текущему process list при старом failed count.
4. Сохранить отдельную текущую семантику
   `ApplicationGrouper.compressedAttributionProcessCount`: это число members
   текущего FAST tree с доступным cached value. UI/report labels не утверждают,
   что сумма per-app counts равна global pair.
   Имена/структура `WebUiPayload` и `WebUiProcessSummary` не меняются, поэтому
   прямые fixtures `webuicheck/src/Main.kt` продолжают компилироваться. Если
   schema-shaped API всё же потребуется, это coordinator glue после Wave 1,
   не скрытая правка Agent 3.
5. Формулировать global coverage как `Last FULL attribution: X measured, Y
   attempts failed` рядом с timestamp/age. Per-app coverage явно относится к
   текущим members.
6. Успешный следующий FULL атомарно заменяет map, пару и timestamp. Failed
   scheduled FULL сохраняет всю прежнюю attribution state и добавляет warning.
7. Новая `LiveSamplingSession` не наследует cache/deadline и начинает с FULL.

Тесты:

- FULL `8/3`, затем FAST с одним старым и одним новым PID: global pair остаётся
  `8/3`, новый PID и ancestor partial;
- per-app count отражает только текущих members с cached values и намеренно не
  обязан сходиться с global `8`;
- следующий FULL заменяет оба global count одновременно;
- failed scheduled FULL + FAST сохраняет прежние pair/timestamp и warning;
- PID reuse не наследует attribution;
- новая session начинает FULL независимо от возраста предыдущего FULL;
- formatter различает `Last FULL` global coverage и current member coverage;
- private helpers имеют префикс `Attribution*`.

Проверка: `./kotlin build`, `./kotlin test -m core`.

### Agent 4 — UI semantics and correctness

Эксклюзивное владение:

- `core/src/dev/yoda/harmon/report/ProcessPage.kt`;
- `core/test/ProcessPageTest.kt`;
- `webuitest/test/ProcessTreeUiTest.kt`.

Agent 4 не меняет HTTP auth, server, fixtures или runtime. Он получает из этого
плана стабильный текстовый контракт Agent 3, поэтому обновление label не требует
ожидания attribution commit и остаётся параллельным.

Реализация и tests:

1. Sort с `scope === "identity"` (`PID` или `Process`) остаётся валидным при
   любом preset/toggle metric columns. Sort заменяется default только если
   скрыта выбранная metric column.
2. Для PID и Process отдельно проверить ascending/descending row order,
   `aria-sort`, смену preset и toggle metric column; состояние сохраняется
   через poll, Snapshot, WARMING и Resume.
3. `formatBytes` после округления до десятых повышает unit, если rounded value
   достиг `1024.0`; повторять promotion на каждой границе, включая
   `1_048_575 → 1.0 MiB`, GiB/TiB и верхнюю поддержанную единицу.
4. System details/global coverage подписать как датированный `Last FULL
   attribution: X measured, Y attempts failed` с timestamp/age. Отдельный
   per-application coverage остаётся в `reportText`, формируемом Agent 3, и
   описывает current members with cached values; новую table label/schema Agent
   4 не добавляет. На сконструированном payload, где global pair и current
   member count различаются, проверить global HTML string и встроенный
   `reportText` как две разные формулировки.
5. Private helpers называются `ProcessUi*`; `core/test/TestFixtures.kt` и
   `webuitest/test/HarnessFixture.kt` не меняются.

Проверка:

```shell
./kotlin build
./kotlin test -m core
./kotlin test -m webuitest
```

### Agent 5 — isolated low-risk polish

Эксклюзивное владение:

- `config/harmon.conf.example`;
- `test/native/processes_test.c`.

Реализация:

1. Описать UI sampling как demand-driven работу при видимой Live-вкладке с
   периодом `webSampleSeconds`, а не постоянный секундный сбор.
2. В allocation-failure branch `hm_check_process_listing` добавить отсутствующий
   `CHECK("processes.zero-budget-skips-region-attribution", 0, "out of memory")`.
   `test/NativeCTest.kt` уже содержит имя и остаётся у Agent 1.

Проверка: `./kotlin build`, `scripts/test-native.sh processes.`,
`git diff --check`.

## Wave 1 integration barrier

Coordinator получает SHA, clean status и test report каждого worker и
cherry-pick по одному:

1. Agent 5 — polish;
2. Agent 4 — UI semantics/correctness;
3. Agent 1 — collector/IPC;
4. Agent 3 — attribution;
5. Agent 2 — sampler runtime.

После каждого commit запускаются targeted tests его агента. Glue делается
отдельным integration commit; worker commits не переписываются. После зелёного
debug build и targeted suite coordinator фиксирует `<WAVE1_HEAD>`.

HTTP/security намеренно не идёт параллельно с UI: оба меняют `ProcessPage.kt`
и browser fixtures. Вторая волна начинается только от интегрированного
`<WAVE1_HEAD>` и тем самым устраняет текстовые и семантические конфликты.

## Wave 2 — HTTP/security/lifecycle

| Роль | Worktree | Ветка | База | Test prefix |
|---|---|---|---|---|
| Agent 6 — HTTP/security | `/private/tmp/harmon-fix-http` | `fix/live-http-security` | `<WAVE1_HEAD>` | `LiveHttp*` |

Agent 6 владеет:

- `src/dev/yoda/harmon/web/LiveUiServer.kt`;
- `src/dev/yoda/harmon/web/LiveUiEndpoint.kt`;
- `test/LiveUiEndpointTest.kt`;
- интегрированной версией `core/src/dev/yoda/harmon/report/ProcessPage.kt` и
  `core/test/ProcessPageTest.kt` только для bootstrap/auth JS;
- `webuitest/test/LiveUiServerTest.kt`;
- `webuitest/test/HarnessFixture.kt`, `webuitest/test/ProcessTreeUiTest.kt` и
  `webuicheck/src/Main.kt` только при необходимости auth-aware fixture wiring;
- `src/main.kt` только если новый endpoint contract требует glue поверх Wave 1.

### Server lifecycle and bounded concurrency

1. Представить каждый start immutable `LiveHttpRun` с generation id,
   nonblocking listener, nonblocking `FD_CLOEXEC` self-pipe и собственным
   active-client set/counter. Numeric fd никогда не используется как run
   identity; `EAGAIN` при wakeup write означает, что сигнал уже pending.
2. Accept loop работает на serial queue и делает `poll` одновременно по
   listener и self-pipe read-end. Он только принимает и dispatch-ит clients;
   parsing/serving выполняется на отдельной concurrent dispatch queue.
3. Worker cap фиксирован в 8. Counter/set защищён lifecycle lock; при cap
   accept loop немедленно закрывает новый fd. Нельзя ставить неограниченные
   blocks в очередь за semaphore.
4. Client fd живёт в active set от успешной конфигурации до worker cleanup.
   Перед добавлением accepted fd accept loop под lifecycle lock проверяет, что
   immutable run всё ещё текущий и не `CLOSING`; иначе fd сразу закрывается и
   worker не dispatch-ится.
   Worker под lifecycle lock сначала удаляет `(generation, fd)` из set и
   уменьшает counter, сигналит drain condition, затем отпускает lock и только
   после этого закрывает fd. После удаления worker не делает с fd ничего,
   кроме собственного `close`; поэтому fd ещё не может быть переиспользован в
   момент, когда `stop()` решает, нужен ли ему shutdown.
5. `stop()` под lifecycle lock помечает run closing, пишет self-pipe и вызывает
   `shutdown(SHUT_RDWR)` только для записей, которые всё ещё находятся в active
   set той же generation. Worker не может одновременно удалить/закрыть такой
   fd, поэтому shutdown никогда не попадает в уже переиспользованный номер.
   Listener из другого thread не закрывается, пока accept loop в `poll`.
6. После initiation `stop()` ждёт accept+worker drain через condition variable,
   которая **отпускает lifecycle lock на время ожидания**. Верхняя граница —
   5 секунд: она покрывает 2-секундный request deadline и shutdown wakeup. При
   timeout run остаётся `CLOSING`, новый `start()` отклоняется до фактического
   drain, а bounded diagnostic сообщает незавершённые counters; deadlock под
   lifecycle lock запрещён.
7. Accept loop после self-pipe wakeup сам закрывает listener и pipe ends; каждый
   worker закрывает собственный client fd по описанному порядку. Последний
   участник отмечает generation drained и будит waiters. Следующий listener
   разрешён только после полного drain предыдущего run.
8. Проверять результаты pipe/fcntl/poll/setsockopt/shutdown там, где ошибка не
   является ожидаемым terminal state; закрывать затронутый ресурс и выдавать
   bounded diagnostic.
9. На listener и каждом accepted fd сразу установить и проверить необходимые
   `SO_NOSIGPIPE`, send/receive timeout и `FD_CLOEXEC` (`F_GETFD` затем
   `F_SETFD(old | FD_CLOEXEC)`). Accepted fd не dispatch-ится до успеха.
10. Сохранить абсолютный 2-секундный deadline и size bound для полного HTTP
   request header. Один drip-fed client не задерживает здорового worker.

Из-за KTC-5573 Agent 6 может вынести минимальный public socket-configuration
helper и проверить его через `socketpair` в `test/LiveUiEndpointTest.kt`.

### Host and token contract

Cookie не используется: cookie для `127.0.0.1` отправляется на все порты, даже
если имя содержит port, поэтому другой loopback service смог бы получить
secret.

1. `LiveUiEndpoint.url` становится
   `http://127.0.0.1:<port>/#token=<64-hex>`. Fragment не отправляется HTTP
   server и не попадает в request target/referrer.
2. Root HTML shell не содержит telemetry и может отдаваться без auth. Bootstrap
   запускается **только** при `pageMode === "live"`; snapshot mode до любых
   storage/history операций сразу идёт по прежнему self-contained render path.
   В начале live JS:

   - берёт token из fragment, проверяет форму;
   - сохраняет его в `sessionStorage` данного origin (origin включает port);
   - немедленно заменяет текущую history entry через
     `history.replaceState(..., "/")` до первого API fetch;
   - при reload использует token из `sessionStorage`.

   Доступ к `sessionStorage` и `history.replaceState` обёрнут в `try/catch`:
   storage/history policy failure не запускает fetch/lease, а показывает
   `run harmon ui again`. Это гарантирует отсутствие uncaught `SecurityError`.

3. Browser API calls используют относительный `/api/live?watch=1` и ровно один
   `Authorization: Bearer <token>` (либо один заранее выбранный эквивалентный
   header). Query string, cookies, DOM и постоянный URL token не содержат.
4. Все `/api/live` browser routes требуют constant-time header auth. Query auth
   разрешён только capture-free native probe по
   `LiveUiEndpoint.apiUrl = /api/live?token=...` без `watch=1`, потому что
   существующий native HTTP bridge умеет передать только URL.
5. Missing/stale session token показывает понятное `run harmon ui again` и не
   продлевает lease. Saved Snapshot page остаётся self-contained, не обращается
   к `sessionStorage`/`history.replaceState` и не делает network fetch.
6. До auth и routing parser требует ровно один case-insensitive `Host` с
   canonical value `127.0.0.1:<actualPort>` после допустимого OWS. Missing или
   duplicate — `400`, другое имя/port, включая `localhost`, — `421`. Только
   canonical `127.0.0.1` URLs являются поддержанными.
7. Не выдавать `Set-Cookie`; два Harmon instances на разных ports имеют
   независимые `sessionStorage` origins и не передают secrets друг другу.
8. Удалить bridge-backed default
   `LiveUiLauncher.open(store: LiveUiEndpointStore = LiveUiEndpointStore())`.
   Launcher принимает store явно, а `src/main.kt` создаёт его на app wiring
   boundary; method reference/glue адаптируется в этой последовательной wave.

### Agent 6 tests and acceptance

- stalled partial header и healthy request идут одновременно; healthy response
  приходит значительно раньше 2 секунд;
- девятый concurrent client при cap 8 отклоняется без unbounded descriptors или
  queued work;
- rapid `start/stop/start` многократно не оставляет old accept loop, не закрывает
  fd новой generation и завершается bounded;
- stop, ожидающий worker completion, отпускает lifecycle lock; test принудительно
  завершает worker во время wait и исключает deadlock;
- client cleanup удаляет fd из active set до `close`; stress с немедленным
  переиспользованием номера подтверждает, что поздний stop не вызывает
  `shutdown` на чужом socket;
- listener/accepted fd options и `FD_CLOEXEC` проверены; injected setup failures
  закрывают fd;
- Host: valid, missing, duplicate, wrong port, `localhost`;
- fragment bootstrap приводит browser к token-free displayed URL; reload в той
  же tab работает через `sessionStorage`; polling target token-free и несёт auth
  header; cookies отсутствуют;
- snapshot `file://` рендерится в Chromium и WebKit без storage/history
  exception и без network request;
- два ports изолированы, wrong/stale token не вызывает `onWatch`;
- native query probe остаётся capture-free, требует верный token и не разрешает
  query auth для `watch=1`;
- Wave 1 PID/Process sort и byte-boundary tests остаются зелёными;
- private top-level helpers получают префикс `LiveHttp*`, общий
  `core/test/TestFixtures.kt` не меняется.

Проверка:

```shell
./kotlin build
./kotlin test -m core
./kotlin test -m harmon
./kotlin test -m webuitest
```

Agent 6 передаёт один или несколько тематических commits, основанных только на
`<WAVE1_HEAD>`. Coordinator cherry-pick/fast-forward-ит их, повторяет targeted
tests и лишь затем начинает общую документацию.

## Performance evidence owned by coordinator

Production code не получает per-sample logging. Agent 2 diagnostics callback
default-no-op используется только детерминированными tests.

Coordinator добавляет `scripts/smoke-live-performance.py` с двумя явными
режимами и machine-readable JSON summary:

1. `collector-only` без root:

   - запускает release `harmon-collector --allow-unprivileged` с явными
     `--allowed-uid <current uid>` и `--allowed-gid <current gid>` на временном
     Unix socket;
   - создаёт до примерно 1000 временных `/bin/sleep` children, пока snapshot
     `totalProcessCount` не достигнет цели или явного system limit;
   - выполняет v3 `PROBE`, `FULL` и строго последовательные `LIVE_FAST`, измеряя
     wall duration, FAST p95 и cumulative-process-CPU delta;
   - отдельно измеряет idle collector без requests;
   - всегда завершает children/collector через terminate, wait и `finally`,
     удаляет только собственный temp directory.

2. `live-end-to-end` только при уже доступной авторизации:

   - принимает явные endpoint file и agent/collector PIDs, ничего не устанавливает
     и не перезапускает;
   - активирует Live тем же header-auth contract, измеряет 90 секунд cumulative
     CPU delta обоих процессов и проверяет observed payload profiles;
   - прекращает renew и подтверждает возврат к idle; secret никогда не печатает.

Разделение evidence обязательно:

- deterministic `LiveUiSamplerTest` доказывает `maxInFlight == 1`, missed-slot
  skipping, отсутствие queue/catch-up и FULL cadence;
- collector-only smoke измеряет idle CPU и FAST latency collector, но не
  выдаётся за end-to-end Live CPU;
- end-to-end smoke подтверждает ≤15% только когда доступны оба PID и рабочий
  endpoint. Если root/installed service недоступен, это честный verification
  caveat, а не подмена unprivileged цифрами.

Цели и источник evidence для подходящей машины примерно с 1000 PID:

- collector-only: idle collector ≤1% CPU и unprivileged `LIVE_FAST` p95
  ≤500 мс. Эти числа — воспроизводимая нижняя граница стоимости, не замена
  привилегированному end-to-end результату;
- live-end-to-end: суммарный active Live average agent+collector ≤15% за
  90 секунд и, если payload timing доступен, привилегированный FAST p95
  ≤500 мс;
- deterministic sampler tests: max in-flight 1, missed slots не накапливаются,
  `FULL` не чаще одного раза в 30 секунд внутри continuous generation.

Root smoke выполняется только при существующей авторизации. `harmon setup`,
изменение launchd service или получение sudo в этот plan не входят.

## Authoritative docs and final integration

После стабилизации Wave 2 coordinator одним docs/integration commit:

- обновляет `README.md`: fragment bootstrap, token-free displayed URL,
  `sessionStorage`, visible-Live sampling;
- обновляет `docs/architecture.md`: absolute IPC deadlines, sequential
  collector, self-pipe HTTP lifecycle, worker cap, strict Host и auth boundary;
- обновляет `docs/collection.md`: overrun cadence, FULL-on-reactivation,
  global dated FULL pair против current per-app coverage;
- обновляет `docs/native-testing.md`: deadline/transport checks, binary
  staleness и performance harness modes;
- подтверждает protocol v3 и Web UI schema v2;
- после переноса всего durable поведения удаляет этот active plan, как требует
  `CLAUDE.md`. Reviewed plan, code и его удаление остаются одной integration
  series, plan не архивируется.

Обязательная финальная проверка:

```shell
git diff --check
./kotlin build
./kotlin test
./kotlin build --variant release
scripts/test-native.sh framing.
scripts/test-native.sh socket.
scripts/test-native.sh processes.
scripts/test-native.sh --sanitize
```

Дополнительно запускаются collector-only performance smoke и, при наличии
prerequisites, 90-секундный live-end-to-end smoke.

После зелёной проверки независимый reviewer запускает Claude `/code-review`
на `<BASE_SHA>..<INTEGRATION_HEAD>` с effort `xhigh`. Coordinator проверяет
каждый finding по исходникам, исправляет подтверждённые отдельным commit и
повторяет affected tests плюс полный suite.

Только coordinator после явного подтверждения пушит integration branch.
Worktrees удаляются лишь когда чисты, а commits достижимы из integration branch;
никакие существующие пользовательские worktrees не затрагиваются.

## Checklist для plan review

Claude должен отдельно подтвердить:

1. Wave 1 ownership и Wave 2 barrier исключают file conflicts;
2. test prefixes и запрет `TestFixtures.kt` исключают package-less collisions;
3. collector deadline покрывает весь frame и slow-drip без overlap capture;
4. activation reservation гарантирует первый `WARMING` без deadlock;
5. cadence выбирает первый будущий tick и на success, и на error;
6. single-shot sampler и atomic generation check отвергают old result;
7. self-pipe/drain/worker-cap contract закрывает Darwin accept и listener/client
   fd-reuse races без ожидания под lifecycle lock;
8. live-only fragment + `sessionStorage` не раскрывает token другому loopback
   port, snapshot `file://` не выполняет bootstrap, native probe остаётся
   capture-free;
9. global FULL pair и current per-app count честно различены;
10. performance claims привязаны к воспроизводимому источнику evidence;
11. scope не требует protocol/schema bump и не пропускает cross-wave glue.
