# Homebrew installation и `harmon setup`

## Overview

Заменить установку из checkout через 177 строк imperative shell на пользовательский поток:

```shell
brew install <tap>/harmon
harmon setup
harmon status
```

Homebrew раздаёт один готовый arm64 tarball, в котором `harmon` и `harmon-collector` всегда имеют
одну версию. Kotlin/Native внутри formula не собирается. `harmon setup` валидирует окружение,
создаёт user-owned app/config/LaunchAgent, затем один раз повышает привилегии и устанавливает
root-owned collector/LaunchDaemon.

Этот документ — только план. В ветке разделения бинарей ни одна команда setup/status, formula или
release packaging не реализуется.

## Context (from discovery)

- После соседнего плана проект имеет два app-модуля и два release output:
  `harmon.kexe` и `harmon-collector.kexe`.
- Текущий `scripts/install.sh` одновременно собирает проект, копирует ресурсы, ищет signing
  identity, делает `sed` по XML, вызывает `launchctl` в двух доменах и чистит Notification Center.
- Текущие ресурсы:
  - `launchd/Harmon.Info.plist`;
  - `launchd/Harmon.icns`;
  - `config/harmon.conf.example`;
  - два plist template, которые после typed generation больше не нужны.
- User state:
  - `~/Library/Application Support/Harmon/Harmon.app`;
  - `~/.config/harmon/config`;
  - `~/Library/LaunchAgents/dev.yoda.harmon.agent.plist`;
  - `~/Library/Logs/Harmon/`.
- System state:
  - `/Library/PrivilegedHelperTools/harmon-collector`;
  - `/Library/LaunchDaemons/dev.yoda.harmon.collector.plist`;
  - `/Library/Logs/Harmon/`;
  - `/var/run/harmon.collector.sock`.
- Protocol already exposes `CollectorProtocol.VERSION`; an incompatible pair fails with
  `Unsupported collector protocol`.
- Application version is currently hard-coded in CLI и `Harmon.Info.plist`, so status/release
  требуют одного shared build-info source.

### Security constraint

`/opt/homebrew` на Apple Silicon принадлежит login user. LaunchDaemon, который root запускает
непосредственно из user-writable prefix, превращает замену файла в тривиальное локальное повышение
привилегий. Поэтому:

- collector **всегда** копируется в root-owned `/Library/PrivilegedHelperTools`;
- daemon plist никогда не ссылается на `Cellar`, `/opt/homebrew` или `/usr/local`;
- formula не содержит `service do`;
- Homebrew `post_install` не пытается делать sudo.

Это свойство архитектуры, а не caveat удобства.

### Upgrade gap

`brew upgrade` заменяет:

- `bin/harmon`;
- `libexec/harmon-collector`;
- `share/harmon/*`.

Но он не имеет права обновить:

- binary внутри user app bundle;
- root helper;
- уже загруженные launchd jobs.

Значит после upgrade установленная пара может остаться старой или стать смешанной. Homebrew не
разрешает честно закрыть это sudo-вызовом из `post_install`. Решение: явный caveat
«после install/upgrade выполните `harmon setup`» и `harmon status`, который называет каждую
копию, её версию и конкретное расхождение.

## Development Approach

- реализовывать поверх уже принятого двухбинарного графа, не возвращать collector в root app;
- сначала сделать testable resource locator, typed plist model и dry command runner;
- затем user phase setup, отдельно system phase, после — status;
- release tarball и formula добавлять только после локального setup/status acceptance;
- все filesystem mutations проектировать идемпотентно: повторный `setup` заменяет generated
  артефакты атомарно, не меняет существующий config и перезапускает jobs в заданном порядке;
- shell оставить только для release orchestration/compatibility, не для генерации plist;
- не прятать privilege boundary: `--system` остаётся документированной публичной формой для
  автоматизации.

## Testing Strategy

- **unit**:
  - resource discovery для Cellar layout, `/usr/local` layout, build tree, symlink и пробелов;
  - typed plist exact structure и XML escaping;
  - setup phase plan без исполнения команд;
  - version/protocol comparison и status exit codes;
  - запрет user phase под root и system phase без root;
- **filesystem integration**: временный synthetic HOME/prefix/root, fake command runner, проверка
  mode/ownership intents и повторного прогона;
- **plist**: каждый generated XML проходит `/usr/bin/plutil -lint`; snapshot tests проверяют
  `ProgramArguments`, uid/gid, paths и launchd domains;
- **release artifact**: распаковать tarball в temp, проверить paths, executable bits,
  `codesign --verify`, версии обоих binaries и отсутствие лишних файлов;
- **formula**: `brew install --build-from-source` здесь означает только распаковку готового
  artifact, затем `brew test`; отдельно audit formula и отсутствие service block;
- **manual e2e**:
  - fresh install;
  - повторный setup;
  - upgrade одной версии на следующую;
  - намеренно старая bundle/helper pair, которую status обнаруживает;
  - uninstall/cleanup остаётся отдельной проверкой существующего script до появления
    `harmon uninstall`.

## Progress Tracking

- mark completed items with `[x]` immediately when done
- add newly discovered tasks with ➕ prefix
- document issues/blockers with ⚠️ prefix
- update plan if implementation deviates from original scope
- keep plan in sync with actual work done

## Solution Overview

Formula устанавливает versioned Cellar layout:

```text
<prefix>/Cellar/harmon/<version>/
  bin/harmon
  libexec/harmon-collector
  share/harmon/
    Harmon.Info.plist
    Harmon.icns
    harmon.conf.example
```

Homebrew создаёт только обычный symlink `<prefix>/bin/harmon`. Никаких launchd mutations на
стадии brew install нет.

Обычный `harmon setup` выполняет:

1. проверку arm64, минимальной macOS, resource layout и обоих binaries;
2. user app bundle, signing, LaunchServices и cache refresh;
3. config-if-absent и `0600`;
4. typed LaunchAgent plist;
5. один re-exec через sudo:
   `sudo <realpath-self> setup --system --uid N --gid M`;
6. root helper и typed LaunchDaemon plist;
7. `bootout → bootstrap → enable → kickstart` collector и agent.

`harmon status` ничего не меняет. Он показывает Homebrew/source copies, installed copies,
protocol/socket и оба launchd jobs, затем возвращает non-zero при любом состоянии, требующем
`harmon setup`.

## Technical Details

### Один источник версии

В `core` появляется `BuildInfo`:

```kotlin
object BuildInfo {
    const val VERSION = "0.4.0"
    const val COLLECTOR_PROTOCOL_VERSION = CollectorProtocol.VERSION
}
```

Точное место генерации/обновления выбирается одно: checked-in source меняется release commit,
а package script сверяет с tag. Из него отвечают `harmon --version` и
`harmon-collector --version`; та же версия подставляется в staged `Harmon.Info.plist` и formula.
Дублирующиеся literals, которые могут разъехаться молча, не остаются.

### Resource discovery без hard-coded Homebrew prefix

`ResourceLocator`:

1. вызывает `_NSGetExecutablePath`;
2. увеличивает buffer и повторяет вызов при необходимости;
3. прогоняет результат через `realpath`, чтобы `<prefix>/bin/harmon` стал реальным Cellar path;
4. сначала проверяет installed layout рядом с executable:
   - `../libexec/harmon-collector`;
   - `../share/harmon/Harmon.Info.plist`;
   - `../share/harmon/Harmon.icns`;
   - `../share/harmon/harmon.conf.example`;
5. если executable лежит под `build/tasks/_harmon_link...`, поднимается до project root,
   подтверждает его через `project.yaml` и берёт:
   - `build/tasks/_harmon-collector_linkMacosArm64<Variant>/harmon-collector.kexe`;
   - `launchd/Harmon.Info.plist`;
   - `launchd/Harmon.icns`;
   - `config/harmon.conf.example`.

Variant (`Debug`/`Release`) читается из task directory текущего executable, поэтому локальная
разработка не смешивает debug agent с release collector. Результат — одна типизированная
`InstallResources`; остальной setup не знает, откуда она пришла.

Ни `/opt/homebrew`, ни `/usr/local` в production-коде нет.

### Проверка платформы

До первой записи setup проверяет:

- `uname -m == arm64`;
- текущую macOS не ниже deployment target release binary;
- self и collector существуют, regular files после `realpath`, executable и имеют одну версию;
- resources читаются;
- обычная фаза не запущена с effective uid 0;
- `--system` запущен с effective uid 0, а `--uid/--gid` валидны и uid не равен 0.

Минимальная macOS не угадывается строкой: при реализации она один раз фиксируется из
`LC_BUILD_VERSION` release binary, становится `BuildInfo.MINIMUM_MACOS`, formula dependency и
setup test fixture одновременно.

### Typed plist

Вместо text templates:

```kotlin
data class LaunchdJob(
    val label: String,
    val programArguments: List<String>,
    val runAtLoad: Boolean,
    val keepAlive: KeepAlive,
    val standardOutPath: String,
    val standardErrorPath: String,
    ...
)
```

Encoder поддерживает только нужные plist primitives (`dict`, `array`, `string`, `integer`,
`true`, `false`) и всегда XML-escapes values. Отдельные constructors создают agent/collector jobs;
невозможно забыть обязательный label или передать uid вместо gid позиционно. Запись идёт во
временный файл рядом с target, затем `plutil -lint`, chmod и atomic rename.

После перехода `launchd/*.plist.template` удаляются: два источника правды хуже одного.

### User phase

Обычный `harmon setup`:

1. создаёт `~/Library/Application Support/Harmon/Harmon.app/Contents/{MacOS,Resources}`;
2. атомарно копирует текущий `harmon` в `Contents/MacOS/harmon` с `0755`;
3. копирует `Harmon.Info.plist` и `Harmon.icns` до подписи;
4. ищет первую подходящую codesigning identity через `security find-identity`; если её нет,
   выполняет ad-hoc `codesign --force --sign -`;
5. проверяет подпись, делает `lsregister -f`;
6. вызывает `killall usernoted NotificationCenter`, принимая «процесс не найден»;
7. создаёт `~/.config/harmon/config` из example только при отсутствии, затем всегда ограничивает
   mode до `0600`;
8. создаёт logs и typed LaunchAgent plist с mode `0600`.

Существующий config не перезаписывается и не merge-ится.

### System phase и privilege boundary

Обычная фаза получает numeric uid/gid до sudo, берёт `realpath` собственного executable и
запускает ровно:

```shell
sudo <realpath> setup --system --uid <uid> --gid <gid>
```

`--system`:

- через `getpwuid(uid)` находит user home только для чтения уже созданного agent plist;
- создаёт root:wheel directories;
- атомарно копирует найденный source collector в
  `/Library/PrivilegedHelperTools/harmon-collector`, затем ставит `root:wheel 0755`;
- генерирует `/Library/LaunchDaemons/dev.yoda.harmon.collector.plist` с uid/gid и socket,
  `root:wheel 0644`;
- не пишет ни одного файла в user home.

Последовательность service mutations:

1. `bootout system/dev.yoda.harmon.collector` (not-found допустим);
2. `bootout gui/<uid>/dev.yoda.harmon.agent` (not-found допустим);
3. `bootstrap system <collector-plist>`;
4. `enable system/dev.yoda.harmon.collector`;
5. `kickstart -k system/dev.yoda.harmon.collector`;
6. `bootstrap gui/<uid> <agent-plist>`;
7. `enable gui/<uid>/dev.yoda.harmon.agent`;
8. `kickstart -k gui/<uid>/dev.yoda.harmon.agent`.

Ошибки bootstrap/enable/kickstart не глотаются. `--system` публичен: MDM/automation может сначала
разложить user files, затем вызвать privileged phase отдельно.

### `harmon status`

Status формирует таблицу:

| Проверка | Источник |
| --- | --- |
| running CLI version | `BuildInfo.VERSION` |
| source collector version | `<layout>/libexec/harmon-collector --version` или build-tree pair |
| installed agent version | bundle binary `--version` |
| installed collector version | root helper `--version` |
| expected protocol | `CollectorProtocol.VERSION` |
| live collector protocol/socket | IPC connect + envelope version/decode |
| agent service | `launchctl print gui/<uid>/dev.yoda.harmon.agent` |
| collector service | `launchctl print system/dev.yoda.harmon.collector` |

Он различает:

- «brew/source обновлён, setup ещё не выполнен»;
- «bundle и helper разных версий»;
- «файлы одной версии, но daemon ещё исполняет старую копию» — PID/executable path и повторный
  protocol probe;
- socket missing/refused;
- service unloaded/failed.

Любое расхождение даёт exit 1 и точное действие: `Run harmon setup`. Здоровая установка даёт 0.
Status не вызывает sudo и не перезапускает службы.

### Release tarball

Release packaging:

- обязательный `./kotlin build --variant release`;
- берёт оба `.kexe` из одного checkout/commit;
- проверяет одинаковый `--version`;
- ставит ad-hoc signatures на standalone binaries и проверяет их;
- staging layout ровно `bin/`, `libexec/`, `share/harmon/`;
- tarball называется `harmon-<version>-macos-arm64.tar.gz`;
- публикуются SHA-256 и provenance к одному GitHub Release/tag.

Оба binaries нельзя выпускать раздельными assets: их парность обеспечивается формой artifact, а
не release discipline.

### Formula

Formula:

```ruby
class Harmon < Formula
  desc "..."
  homepage "..."
  url ".../harmon-X.Y.Z-macos-arm64.tar.gz"
  sha256 "..."
  license "GPL-3.0-only"

  depends_on arch: :arm64
  depends_on macos: :<pinned-minimum>
end
```

`install` раскладывает уже готовые paths в `bin`, `libexec`, `share/"harmon"`. `test do` вызывает
`--version` у обоих binaries. `caveats` прямо говорит:

- выполнить `harmon setup` после install;
- повторить после каждого upgrade;
- проверить `harmon status`.

`service do` и privileged `post_install` отсутствуют.

Тарбол скачивается Homebrew/curl без quarantine provenance браузера, поэтому Developer ID и
notarization для этого канала не требуются. Standalone binaries ad-hoc signed в release artifact;
app bundle подписывается заново на машине пользователя после копирования ресурсов.

## What Goes Where

- **Implementation Steps** (`[ ]`): будущая реализация setup/status/release/formula.
- **Post-Completion** (без чекбоксов): наблюдение за реальными upgrades и решение, нужен ли
  отдельный uninstall command.

## Implementation Steps

### Task 1: Общая версия и version commands

**Files:**
- Create: `core/src/dev/yoda/harmon/BuildInfo.kt`
- Modify: root CLI/version output
- Modify: `harmon-collector` CLI
- Modify: `launchd/Harmon.Info.plist` или release staging generation
- Create/Modify: version tests

- [ ] убрать hard-coded `0.4.0` из CLI
- [ ] добавить `harmon-collector --version`
- [ ] связать version, protocol version и minimum macOS с одним typed source
- [ ] проверять release tag против `BuildInfo.VERSION`

### Task 2: ResourceLocator для brew и build tree

**Files:**
- Create: `src/dev/yoda/harmon/setup/ExecutablePath.kt`
- Create: `src/dev/yoda/harmon/setup/InstallResources.kt`
- Create: `test/ResourceLocatorTest.kt`

- [ ] реализовать `_NSGetExecutablePath` с resize loop и `realpath`
- [ ] реализовать Cellar sibling layout без знания prefix
- [ ] реализовать debug/release build-tree layout
- [ ] валидировать regular/executable/readable paths и парность версий
- [ ] покрыть symlink, spaces, отсутствующий collector/resource и смешанные variants

### Task 3: Typed plist model и atomic writer

**Files:**
- Create: `src/dev/yoda/harmon/setup/Plist.kt`
- Create: `src/dev/yoda/harmon/setup/LaunchdJobs.kt`
- Create: `test/{PlistTest,LaunchdJobsTest}.kt`
- Delete after switch: `launchd/*.plist.template`

- [ ] реализовать ограниченный typed plist encoder с XML escaping
- [ ] собрать agent и collector structures со всеми текущими launchd keys
- [ ] писать temp → `plutil -lint` → chmod/chown intent → rename
- [ ] snapshot-тестами закрепить args, domains, uid/gid, paths и modes

### Task 4: CommandRunner и validation

**Files:**
- Create: `src/dev/yoda/harmon/setup/CommandRunner.kt`
- Create: `src/dev/yoda/harmon/setup/SetupValidation.kt`
- Create: corresponding tests

- [ ] выполнять команды argv-массивом без shell interpolation
- [ ] различать допустимый not-found для bootout/killall и настоящий failure
- [ ] проверить arm64, minimum macOS, uid/root phase и resources до mutations
- [ ] дать tests fake runner и записывать точную последовательность

### Task 5: User phase `harmon setup`

**Files:**
- Create: `src/dev/yoda/harmon/setup/UserSetup.kt`
- Modify: root `cli/Cli.kt`, `main.kt`
- Create: setup parser/filesystem tests

- [ ] добавить `Command.Setup(system = false, ...)`
- [ ] собрать app bundle/resources с правильными modes
- [ ] выбрать trusted identity или ad-hoc и проверить codesign
- [ ] зарегистрировать bundle и обновить Notification Center caches
- [ ] создать config iff absent, всегда ужесточить до `0600`
- [ ] создать typed LaunchAgent
- [ ] доказать идемпотентность повторным integration test

### Task 6: System phase и один sudo re-exec

**Files:**
- Create: `src/dev/yoda/harmon/setup/SystemSetup.kt`
- Modify: setup command dispatch
- Create: privilege/sequence tests

- [ ] re-exec exact realpath self с `--system --uid --gid`
- [ ] запретить system phase без root и uid 0
- [ ] копировать collector только в root-owned helper path
- [ ] создать typed LaunchDaemon
- [ ] выполнить заданную bootout/bootstrap/enable/kickstart sequence
- [ ] не писать в user home из system phase

### Task 7: `harmon status`

**Files:**
- Create: `src/dev/yoda/harmon/setup/Status.kt`
- Modify: IPC protocol/client inspection seam
- Modify: root CLI/help
- Create: `test/StatusTest.kt`

- [ ] собрать версии source и installed copies
- [ ] проверить expected/live protocol и socket
- [ ] разобрать состояния двух launchd jobs без sudo
- [ ] вернуть 0 только для полностью согласованной running pair
- [ ] назвать upgrade gap и `harmon setup` в каждом actionable failure

### Task 8: Release artifact

**Files:**
- Create: `scripts/package-release.sh`
- Create: release workflow/configuration
- Modify: release documentation

- [ ] собирать оба release binaries из одного commit
- [ ] сверять versions и signatures
- [ ] создавать точный staging layout и один deterministic tarball
- [ ] публиковать SHA-256 рядом с GitHub Release
- [ ] распаковать artifact в test и прогнать smoke checks

### Task 9: Formula

**Files:**
- Create: `Formula/harmon.rb` в выбранном tap
- Create/Modify: formula update automation

- [ ] поставить `arch: :arm64` и pinned macOS minimum
- [ ] установить bin/libexec/share без build toolchain
- [ ] добавить caveats после install и upgrade
- [ ] не добавлять `service do` или sudo post_install
- [ ] выполнить formula test и audit

### Task 10: Переход с source installer и документация

**Files:**
- Modify: `README.md`
- Modify: `docs/architecture.md`
- Modify: `CLAUDE.md`
- Modify/Delete: `scripts/install.sh`
- Preserve/Modify: `scripts/uninstall.sh`

- [ ] сделать Homebrew + setup основным documented flow
- [ ] оставить source-build путь через тот же `harmon setup`
- [ ] убрать plist templates и sed flow после переключения
- [ ] описать privilege boundary, helper copy и отсутствие formula service
- [ ] описать status/upgrade caveat

### Task 11: Verify acceptance criteria

- [ ] fresh `brew install` → `harmon setup` → healthy `harmon status`
- [ ] второй setup не меняет config и остаётся зелёным
- [ ] `brew upgrade` без setup даёт точное status mismatch
- [ ] setup после upgrade обновляет обе installed copies и перезапускает jobs
- [ ] collector plist указывает только на root-owned helper
- [ ] formula не содержит service/post_install privilege escalation
- [ ] tarball всегда содержит одну version pair

## Post-Completion

*Требует времени и наблюдения — без чекбоксов.*

- проверить несколько последовательных Homebrew upgrades на реальных Cellar symlinks;
- проверить поведение с локальной Developer ID identity и без неё;
- решить, нужен ли `harmon uninstall` как симметричная typed команда или достаточно сохранить
  отдельный uninstaller;
- оценить notarized release channel отдельно, если tarball начнут раздавать браузером, а не только
  через Homebrew.
