# MopedTrackV2 Developer & Agent Guide

This document provides a comprehensive overview of **MopedTrackV2** for AI agents and developers working on this codebase.

---

## 1. Project Overview & Purpose

**MopedTrackV2** is an Android application designed to track, display, and verify road segment completion/coverage for moped/bike riders. Riders can select geographical regions, import GPX track logs (individually or in bulk via folder import), or record rides to automatically mark road segments as completed based on spatial matching algorithms.

### Core Capabilities:
- **Region Management:** Search regions via Nominatim (OSM), fetch road networks via Overpass API, and store them locally.
- **Road Segment Matching:** Evaluates GPX trace points against road geometry using spatial indexing, interpolation, and distance corridor checks.
- **Interactive Map:** Displays completed/uncompleted segments, regions, and GPX traces on a vector map using MapLibre Android SDK.
- **GPX Import & WorkManager:** Bulk imports GPX files from storage in the background and rescans traces when matching settings change.
- **Customization:** Adjustable match radius, coverage percentage threshold, and map layer visibility toggles.

---

## 2. Tech Stack & Key Libraries

| Category | Technology / Library | Version / Details |
|---|---|---|
| **Language** | Kotlin | Target SDK 37, Min SDK 26 |
| **UI Framework** | Jetpack Compose | Compose BOM, Material 3, Adaptive Layouts |
| **Navigation** | Navigation 3 | `androidx.navigation3.runtime`, `androidx.navigation3.ui` |
| **Map Rendering** | MapLibre Android SDK | Native vector rendering, MapLibreCompose wrapper |
| **Database** | Room Database | Version 6 (with schema migrations 1->2, 2->3, 3->4, 4->5, 5->6), KSP codegen |
| **Preferences** | DataStore Preferences | `SettingsDataStore` |
| **Networking** | Retrofit 2 + Moshi | Moshi KSP codegen, OkHttp, Overpass API with multi-endpoint fallback |
| **Background Processing** | WorkManager | `CoroutineWorker` (`GpxBulkImportWorker`, `GpxRescanWorker`, `RegionRefreshWorker`) |
| **Permissions** | Accompanist Permissions | Location permissions handling |
| **Concurrency** | Kotlin Coroutines & Flow | StateFlow / SharedFlow architecture |
| **Testing** | JUnit4, Mockito, Coroutines Test | Unit test coverage for Repository, Engine, ViewModels, Polyline utils |

---

## 3. Architecture & Project Structure

The project follows **Clean Architecture** principles with clear separation into Data, Domain, Presentation, and Utility layers.

```
app/src/main/java/com/example/mopedtrackv2/
├── MainActivity.kt                      # Main Activity entry point with edge-to-edge support
├── MopedApplication.kt                  # Application class: initializes MapLibre & Repository singleton
│
├── data/                                # Data Layer
│   ├── gpx/                             # GPX Export (SAF DocumentFile + fallback)
│   │   └── GpxExporter.kt               # AndroidGpxExporter writing GPX 1.1 files
│   ├── local/                           # Room Database, Entities, DAOs & Migrations
│   │   ├── AppDatabase.kt               # Room database definition (Entities: Region, RoadSegment, ImportedGpx, DebugTrace)
│   │   ├── dao/                         # RegionDao, RoadSegmentDao, ImportedGpxFileDao, DebugTraceDao
│   │   └── entity/                      # RegionEntity, RoadSegmentEntity, ImportedGpxFileEntity, DebugTraceEntity
│   ├── preferences/                     # Settings DataStore implementation
│   │   └── SettingsDataStore.kt         # Preferences for match radius, threshold, layer visibility, ride GPS & export URI
│   ├── remote/                          # Remote Data Sources
│   │   ├── OsmApi.kt                    # Nominatim search API & Overpass query API interface
│   │   └── RetrofitClient.kt            # Retrofit setup with fallback Overpass endpoints
│   ├── repository/                      # Repository Implementation
│   │   └── MopedRepositoryImpl.kt       # Handles OSM data fetching, live matching, GPX export, DB operations, settings
│   ├── service/                         # Foreground Ride Tracking Service & Coordinator
│   │   ├── RideTraceStore.kt            # Crash-safe append-only disk journal + atomic state store (filesDir/active_ride)
│   │   ├── RideTrackingController.kt    # Thread-safe StateFlow state machine & active trace buffer with disk recovery
│   │   └── RideTrackingService.kt       # Foreground location service (PRIORITY_HIGH_ACCURACY) + live segment matching
│   └── worker/                          # Background Workers
│       ├── GpxBulkImportWorker.kt       # Scans folder tree for .gpx, parses, matches, and persists
│       ├── GpxRescanWorker.kt           # Rescans all GPX files against segments when parameters change
│       └── WeeklyRegionRefreshWorker.kt # RegionRefreshWorker: weekly unmetered-network region refresh
│
├── domain/                              # Domain Layer
│   ├── engine/                          # Core Algorithmic Engine
│   │   ├── LiveSegmentMatcher.kt        # Sliding trace window & padded BBox real-time matching helper
│   │   └── SegmentMatcherEngine.kt      # Interpolation, TraceSpatialIndex, segment coverage evaluation
│   ├── gpx/                             # GPX Parsing & Serialization
│   │   ├── GpxParser.kt                 # XML PullParser for GPX track points
│   │   └── GpxWriter.kt                 # Standards-compliant GPX 1.1 XML serializer & filename builder
│   ├── model/                           # Domain Data Models
│   │   ├── OsmPinCategory.kt            # OSM mapping note categories
│   │   ├── Region.kt                    # Domain representation of a Region
│   │   ├── RegionRefreshModels.kt       # RegionRefreshResult summary stats from non-destructive refresh
│   │   ├── RideTrackingModels.kt        # RideRecordingState enum & TrackPoint data class
│   │   ├── RoadSegment.kt               # Domain representation of a Road Segment
│   │   ├── StatsSummary.kt              # Overall & per-region coverage stats + GPX archive totals
│   │   └── UnitSystem.kt                # IMPERIAL / METRIC display units enum
│   └── repository/                      # Repository Interfaces
│       └── MopedRepository.kt           # Central repository contract
│
├── presentation/                        # Presentation Layer (Jetpack Compose + ViewModel)
│   ├── MopedApp.kt                      # Root Composable managing Navigation 3 backstack & screens
│   ├── ViewModelFactory.kt              # Manual DI factory for ViewModels
│   ├── home/                            # Main Map Screen
│   │   ├── HomeScreen.kt                # Interactive map view, layer controls, location button, info sheet
│   │   ├── HomeViewModel.kt             # Viewport segment query, match settings flows, map state
│   │   └── MapLibreCompose.kt           # AndroidView wrapper for MapLibre MapView & lifecycle
│   ├── importgpx/                       # GPX Import Screen
│   │   ├── ImportGpxScreen.kt           # File/Folder picker, worker progress tracking, list of files
│   │   └── ImportGpxViewModel.kt        # WorkManager launch & file management
│   ├── regions/                         # Region Management Screen
│   │   ├── ManageRegionsScreen.kt       # Search regions online, download OSM roads, delete regions
│   │   └── ManageRegionsViewModel.kt    # Nominatim search, Overpass downloading state
│   ├── settings/                        # App Settings Screen
│   │   ├── SettingsScreen.kt            # Adjust match radius, coverage threshold, debug options
│   │   └── SettingsViewModel.kt         # DataStore settings bindings & rescan triggers
│   └── stats/                           # Coverage & Ride Archive Stats Screen
│       ├── StatsScreen.kt               # Overall progress, per-region breakdown, and GPX archive cards
│       └── StatsViewModel.kt            # Reactive StatsSummary & UnitSystem UI state
│
└── utils/                               # Utilities & Math Helpers
    ├── HashUtils.kt                     # SHA-256 hex digest helper for GPX deduplication
    ├── MathUtils.kt                     # Haversine distance, bearing, point-to-line segment distance
    └── PolylineUtils.kt                 # Google Polyline format encoder/decoder & JSON parser
```

---

## 4. Key Algorithms & Core Concepts

### 4.1 Highway Filtering & Processing
When importing a region via Overpass API:
- Highways are filtered using regex:
  `^(residential|unclassified|tertiary|tertiary_link|secondary|secondary_link|primary|primary_link|trunk|trunk_link|living_street|service)$`
- **Two-way roads** are duplicated into two directed/paired segments (`pairedSegmentId`). Completing one can optionally auto-complete its pair.
- **One-way roads** remain single directed segments.
- **Dead-end roads** are detected (`isDeadEnd`) when one of their endpoints is not shared with any other segment in the region.

### 4.2 Segment Matcher Engine (`SegmentMatcherEngine.kt`)
1. **Interpolation & Local Road Bearing:** Segments are discretized into points spaced 5 meters apart with local segment bearings (`interpolateSegmentWithBearing`).
2. **Spatial Grid & Movement Bearing (`TraceSpatialIndex`):** Trace segments are indexed in a 2D spatial grid cell structure (`cellSize = 0.005` degrees, ~550m), with pre-computed movement bearings for trace vectors.
3. **Bounding Box Overlap:** Candidate trace segments are rapidly retrieved via spatial grid cells.
4. **Coverage Calculation & Heading Alignment:**
   - Computes sub-point coverage based on spatial distance (`<= matchRadiusMeters`) AND directional alignment (`MathUtils.parallelAngleDifferenceDegrees <= 50.0°`). This prevents false-positive completions on short side streets when driving straight past intersections.
   - **Dead-End Logic:** Lower coverage threshold (max of `50%` or `threshold - 15%`) plus requirement that either the start or end of the segment was hit.
   - **Regular Segment Logic:** Fully completed if coverage >= `coverageThresholdPercent` (default 80%). If coverage is between 70% and 80%, completed if both start and end 30% regions are covered.

### 4.3 Live Ride Tracking, Real-Time Matching & GPX 1.1 Export
- **`RideTrackingService` (`data/service/RideTrackingService.kt`):** Android foreground service (`foregroundServiceType="location"`) that manages `FusedLocationProviderClient` with `PRIORITY_HIGH_ACCURACY` strictly while `RideRecordingState == RECORDING`, controlled by `RideLocationCoordinator` using `pollingIntervalMsFlow` and `minDisplacementMetersFlow`. Immediately releases GPS updates on `PAUSED` or `IDLE` and shows a persistent notification with Pause/Resume/Stop actions.
- **Crash-Safe Disk Persistence & Recovery (`RideTraceStore` / `FileRideTraceStore`):** Every appended `TrackPoint` is immediately appended, flushed, and `fd.sync()`-ed on a dedicated single-thread writer to `filesDir/active_ride/active_ride_points.tsv`, while recording state and `completedSegmentCount` are atomically written via temp-file rename (`active_ride_state.txt`). On app restart after a crash or battery loss, `RideTrackingController` reloads valid points (ignoring any truncated trailing line), restores the ride into `RideRecordingState.PAUSED` so the user can Resume or Save to GPX, and `HomeViewModel` surfaces a recovery Snackbar. Disk draft files are cleared only on `discardRecording()` (or after GPX export succeeds in `saveRideRecording()`).
- **Real-Time Incremental Matching (`LiveSegmentMatcher`):** As new `TrackPoint`s arrive in `RideTrackingController.activeTrace`, `RideTrackingService` triggers `MopedRepository.matchLiveTraceWindow()` (conflated on `Dispatchers.Default`), querying uncompleted `RoadSegmentEntity` rows around the recent trace window padded by `matchRadiusMeters + 10.0` meters and evaluating them with `SegmentMatcherEngine`. Matched segments (plus any `pairedSegmentId`) are immediately marked `isCompleted = true` in `RoadSegmentDao`, updating the map to green (`#2E7D32`) via Room's reactive `Flow`.
- **`GpxWriter` (`domain/gpx/GpxWriter.kt`):** Standards-compliant GPX 1.1 XML serializer generating `MopedTrack_YYYY-MM-DD_HH-mm-ss.gpx` files with `<metadata>`, `<trk>`, `<trkseg>`, and `<trkpt lat="..." lon="..."><ele>...</ele><time>ISO-8601</time></trkpt>` elements.
- **Automatic Export & Deduplication on Save:** Confirming **Save Ride** writes the `.gpx` file via `AndroidGpxExporter` to the configured SAF `gpx_export_tree_uri` (falling back to app external storage `Documents/MopedTrack` if unset or revoked), records its SHA-256 hash (`HashUtils.sha256Hex`) and encoded polyline in `imported_gpx_files` (`isVisibleOnMap = false`) so future `GpxBulkImportWorker` runs skip it, shows `"Ride saved to GPX"`, and resets `activeTrace` to `IDLE`.

### 4.4 Non-Destructive OSM Region Refreshing & Weekly Sync
- **`MopedRepository.refreshRegion(regionId)` (`MopedRepositoryImpl.kt`):**
  1. Fetches the latest OSM road network from Overpass (`fetchOverpassResponse`) *before* modifying Room so network/server errors never wipe existing map data.
  2. Snapshots existing completed `RoadSegmentEntity` rows (`isCompleted`, decoded geometries, and `completedAt` timestamps) into a 2D spatial grid and `osmWayId` lookup.
  3. Builds the new road segments (`buildRoadSegmentsFromOverpass`), preserves `isCompleted = true` and `completedAt` on any incoming segment whose `osmWayId` + endpoints match OR where `>= 80%` of 5-meter interpolated sub-points fall within `matchRadiusMeters` (with heading alignment) of an old completed segment, and propagates completion across divided-road `pairedSegmentId` partners.
  4. Re-evaluates stored `ImportedGpxFileEntity` traces against remaining uncompleted/newly mapped segments via `SegmentMatcherEngine` so previously ridden newly mapped roads automatically complete.
  5. Atomically replaces the region's segments via `RoadSegmentDao.replaceRegionSegments()` inside `database.withTransaction` (`runInTransaction`), returning `RegionRefreshResult`.
- **Manual & Scheduled Execution:** `ManageRegionsViewModel` / `ManageRegionsScreen` expose per-region and "Refresh All" actions with progress indicators and summary Snackbars. `RegionRefreshWorker` (`WeeklyRegionRefreshWorker.kt`) schedules a 7-day `PeriodicWorkRequest` (`UNIQUE_WORK_NAME = "weekly_region_refresh"`, `NetworkType.UNMETERED`) toggled by `weekly_auto_refresh` in Settings.

---

## 5. Navigation & Screen Flow

The application uses **Jetpack Navigation 3** with explicit route keys (`MopedRoute`):
- `MopedRoute.Home`: Main map interface showing road segments, regions, GPX traces, and quick settings toggles.
- `MopedRoute.ManageRegions`: Search OSM for regions (cities, counties), download road networks, and view installed regions.
- `MopedRoute.ImportGpx`: Pick individual `.gpx` files or select folders for background WorkManager import.
- `MopedRoute.Settings`: Configure match radius (meters), coverage threshold (%), and toggle debug overlays.
- `MopedRoute.OsmPins`: View, filter, and export dropped OpenStreetMap mapping notes.
- `MopedRoute.Stats`: Dedicated Stats screen (`StatsScreen` / `StatsPanelContent`, accessible from the hamburger menu `≡`) showing overall coverage, per-region breakdown, and GPX archive totals.

---

## 6. Build, Test, and Execution Commands

| Action | Command / Tool |
|---|---|
| **Build Debug APK** | `./gradlew app:assembleDebug` |
| **Run Unit Tests** | `./gradlew app:testDebugUnitTest` |
| **Run Instrumentation Tests** | `./gradlew app:connectedDebugAndroidTest` |
| **Check Lint & Inspections** | Use IDE `analyze_file` tool or `./gradlew lint` |
| **Deploy to Device** | `deploy(type="ANDROID_APP", module="app", launchType="DEFAULT_ACTIVITY", deployMode="RUN")` |
| **Inspect Device Screen** | `ui_state()` (returns UI XML hierarchy & screenshot) |
| **Interact with UI** | `adb_shell_input(command="tap" / "swipe" / "text" / "keyevent", ...)` |
| **Read Logcat Output** | `read_logcat(serialNumber="", applicationId="com.example.mopedtrackv2", ...)` |

> **Windows Terminal Sandbox Note:** On this Windows host, `java.exe` (Gradle/wrapper) hangs immediately inside the default terminal sandbox, and `adb` / `android` CLI commands are blocked from accessing `~/.android` (`C:\Users\erit1\.android`) and device sockets. Run `./gradlew.bat` builds/tests, `adb`, and `android` CLI commands with `BypassSandbox: true` (after attempting sandboxed only if required by environment rules). When multiple devices are attached, pass `-s <serial>` (e.g., `-s emulator-5554`) to `adb` and `--device=<serial>` to `android`.

### Unit Test Suites:
- `MopedRepositoryImplTest`: Tests OSM downloading, region deletion, segment pairing, live trace window matching, GPX save export/deduplication, and aggregated `getStatsSummaryFlow()` metrics.
- `SegmentMatcherEngineTest`: Tests trace matching, dead-end relaxation, and interpolation.
- `GpxWriterTest`: Tests GPX 1.1 XML serialization, ISO-8601 timestamp formatting, and filename generation.
- `ManageRegionsViewModelTest`: Tests search and import ViewModel state handling.
- `HomeViewModelTest`: Tests ride recording state transitions, stop dialog lifecycle, GPX save snackbar/reset, and MapLibre passive location power priority.
- `RideTrackingControllerTest`: Tests foreground ride recording state machine and GPS location coordinator behavior.
- `SettingsViewModelTest`: Tests settings DataStore bindings and clamping.
- `StatsViewModelTest`: Tests `StatsViewModel` reactive summary/unit-system state, percentage and distance formatting, and zero-segment edge cases.
- `PolylineUtilsTest`: Tests polyline encoding/decoding and JSON parsing fallback.

### 6.1 On-Device Testing & Critic Agent Protocol
For UI features, map integrations, or critical logic changes, agents should perform end-to-end verification on a connected device (emulator or physical phone):
1. **Deploy & Launch:** Use the `deploy` tool to build and install the latest app version on the connected target device.
2. **UI Hierarchy & Visual Inspection (`ui_state`):** Invoke `ui_state` to capture the current screen UI XML node tree and active screenshot. Verify that elements (e.g. MapLibre canvas, region search lists, layer toggles) render correctly.
3. **Automated Touch & Input (`adb_shell_input`):** Use `adb_shell_input` to execute taps on specific screen coordinates, type search queries, swipe across map surfaces, or send system back events.
4. **Log Inspection (`read_logcat`):** Monitor runtime logs for app exceptions, Overpass network errors, or MapLibre initialization warnings.
5. **Critic Agent Verification:** A Critic Agent role should perform a visual/functional regression check after major feature implementations, confirming that interactive screens match expected designs and user flows before closing a task.

---

## 7. Guidelines for AI Agents Working on This Codebase

1. **State Management:** Keep logic inside ViewModels and Repositories. Maintain UI components in Compose clean and stateless where possible.
2. **Database Schema Changes:** Always write explicit migration scripts in `AppDatabase.kt` when updating Room entities, incrementing `version`.
3. **Overpass Network Resilience:** Overpass API can be rate-limited or return errors. Always use `OVERPASS_ENDPOINTS` fallbacks defined in `OsmApi.kt`.
4. **Map Performance:** MapLibre layers should be updated efficiently by reusing GeoJsonSources rather than recreating layers on every camera change.
5. **Precision:** Coordinate calculations should use `MathUtils` and `PolylineUtils` to avoid precision issues.
6. **On-Device Verification:** Validate UI transitions, map interactions, and worker execution on an active device using `deploy`, `ui_state`, `adb_shell_input`, and `read_logcat` rather than relying solely on unit tests.
7. **Knowledge Persistence & Skill Creation:** If you execute a non-trivial process or multi-step workflow multiple times (e.g., adding a new feature, database migration recipe, or map layer integration), **document it in `gemini.md`** or **create a reusable Skill** so future agents can execute it deterministically.

---

## 8. Database Schema & Entity Relationships

The Room database (`mopedtrack_db`) contains four primary entities:

### `regions` (`RegionEntity`)
- Primary Key: `regionId` (String, e.g. OSM Relation/Way ID or custom UUID).
- Fields: `name`, `displayName`, `minLat`, `maxLat`, `minLon`, `maxLon`, `downloadedAt`, `segmentCount`.

### `road_segments` (`RoadSegmentEntity`)
- Primary Key: `segmentId` (String).
- Foreign Key: `regionId` references `regions(regionId)` with `ON DELETE CASCADE`.
- Indices: `Index("regionId")`, spatial BBox index `Index("minLat", "maxLat", "minLon", "maxLon")`.
- Fields: `osmWayId`, `name`, `highwayType`, `isOneway`, `pairedSegmentId` (for two-way roads), `isDeadEnd`, `geometryEncoded` (Google Polyline string), `lengthMeters`, `isCompleted`, `completedAt`.
- **SQL Stats Aggregation (`RoadSegmentDao`):** `getOverallRoadStatsFlow()` and `getRegionStatsFlow()` compute deduplicated segment counts and physical distances (`RoadStatsAggregate`, `RegionStatsRow`) directly in SQLite without loading `geometryEncoded`, grouping paired carriageways by canonical key (`pairedSegmentId` or `MIN(segmentId, pairedSegmentId)`). Unnamed roads (`name IS NULL OR name = '' OR name = 'Unnamed Road'`) are excluded from overall/region totals and tracked separately via `unnamedCompletedSegments`.

### `imported_gpx_files` (`ImportedGpxFileEntity`)
- Primary Key: `fileHash` (SHA-256 string).
- Fields: `fileName`, `importedAt`, `trackPointCount`, `segmentsCompletedCount`, `encodedTrace`, `isVisibleOnMap`, `distanceMeters`, `durationMillis`, `recordedAt`.
- **SQL Stats Aggregation (`ImportedGpxFileDao`):** `getGpxArchiveStatsFlow()` aggregates `COUNT(*)` and `COALESCE(SUM(trackPointCount), 0)` into `GpxArchiveStatsAggregate` without loading `encodedTrace`.

### `debug_traces` (`DebugTraceEntity`)
- Primary Key: `traceId` (String UUID).
- Fields: `pointsEncoded`, `pointCount`, `createdAt`.

---

## 9. MapLibre Vector Map & Viewport Optimization

### 9.1 Viewport Bounding Box Queries
To optimize memory and rendering performance when handling large regions with thousands of road segments:
- The map triggers `onCameraIdle` with the visible latitude/longitude bounds (`minLat`, `maxLat`, `minLon`, `maxLon`).
- `HomeViewModel` queries Room using the BBox index to update `roadSegments` for the active viewport only.

### 9.2 GeoJSON Sources & Layers
- MapLibre layers (`segments-casing-layer`, `segments-layer`, `active-ride-trace-layer`, and debug overlay layers) update dynamically via reusable `GeoJsonSource.setGeoJson()`.
- `active-ride-trace-layer` (`#00E5FF` cyan, `4.5dp` width) is layered above `segments-layer` to render `activeTrace` live while `recordingState != IDLE`, and clears automatically when `recordingState == IDLE`.
- Passive Location Power Rule: `MapLibreCompose` configures `LocationComponent` with `PRIORITY_BALANCED_POWER_ACCURACY` (and seeds last-known location) whenever `recordingState != RECORDING`, reserving `PRIORITY_HIGH_ACCURACY` GPS exclusively for active ride recording.
- Debug overlay draws corridor polygons calculated by `createCorridorPolygon()` around road geometries to visualize coverage corridors.

---

## 10. Common Developer & Agent Tasks

### 10.1 Adding a New Preference / Setting
1. Add Key in `data/preferences/SettingsDataStore.kt`.
2. Expose `Flow` and suspend setter function in `SettingsDataStore` & `MopedRepository`.
3. Wire through `MopedRepositoryImpl`, `SettingsViewModel`, and Compose UI in `SettingsScreen.kt`.

Current `SettingsDataStore` keys (`"settings"` Preferences DataStore):
- `match_radius_meters` (`Int`, default `15`, range `5..30`)
- `coverage_threshold_percent` (`Int`, default `80`, range `50..100`)
- `debug_overlay_enabled` (`Boolean`, default `false`)
- `show_completed_segments` (`Boolean`, default `true`)
- `show_uncompleted_segments` (`Boolean`, default `true`)
- `show_gpx_traces` (`Boolean`, default `true`)
- `hidden_region_ids` (`Set<String>`, default `emptySet()`): Region IDs toggled off in the HomeScreen Map Layers bottom sheet.
- `last_camera_lat`, `last_camera_lon`, `last_camera_zoom`, `last_camera_bearing`, `last_camera_tilt` (`Double`)
- `polling_interval_ms` (`Long`, default `1000L`, range `500L..5000L`): Foreground GPS polling interval while `RECORDING`.
- `min_displacement_meters` (`Float`, default `2.0f`, range `0.0f..15.0f`): Minimum GPS displacement filter while `RECORDING`.
- `gpx_export_tree_uri` (`String?`, default `null`): Persisted SAF `OpenDocumentTree` URI for automatic `.gpx` ride exports (falls back to `Documents/MopedTrack` when unset or revoked).
- `unit_system` (`String`, default `IMPERIAL`): Display unit preference (`IMPERIAL` or `METRIC`).
- `weekly_auto_refresh` (`Boolean`, default `false`): Enables `RegionRefreshWorker` 7-day unmetered-network background refresh across all downloaded regions.

### 10.2 Adding a Room Migration
1. Increment `version` in `@Database` annotation inside `AppDatabase.kt`.
2. Define a migration object `MIGRATION_X_Y = object : Migration(X, Y) { override fun migrate(db: SupportSQLiteDatabase) { ... } }`.
3. Add migration to `.addMigrations(...)` in `AppDatabase.getDatabase()`.

### 10.3 Overpass API Resilience & Performance
If Overpass requests fail due to rate limiting (HTTP 429) or timeouts:
- The app rotates through global fallback endpoints in `OVERPASS_ENDPOINTS` (`overpass-api.de`, `kumi.systems`, `private.coffee`). Dead hosts (`nchc.org.tw`) and country-restricted regional instances (`osm.ch`, `freemap.sk`) are excluded.
- Network read timeout is set to 45s per endpoint to quickly bypass unresponsive mirrors.
- Overpass QL queries target road highways directly (`["highway"~"..."]`) and exclude non-road bridge queries to maximize query execution speed and minimize payload size.
- Always verify query bounds (`bbox`) are appropriately sized before sending requests.

---

## 11. Recommended Agent Skills

When working on specific sub-systems or features in **MopedTrackV2**, AI agents should leverage the following installed skills:

| Skill Name | Description / Primary Use Case |
|---|---|
| `navigation-3` | Best practices and reference patterns for **Jetpack Navigation 3** (`androidx.navigation3`), route definitions, backstack state, and scene transitions. |
| `edge-to-edge` | Guidelines for managing system bar insets, status bar padding, and IME insets with `enableEdgeToEdge()`. |
| `jetpack-compose-performance` | Optimizing Compose UI recompositions, list performance, and MapLibre overlay state stability. |
| `testing-setup` | Expanding unit tests, coroutine test dispatchers, repository mocks, and instrumentation test infrastructure. |
| `android-cli` | Interacting with ADB, running device commands, inspecting UI trees, and managing emulators. |
| `find-skills` | Discovering and installing additional skills for specialized Android development workflows. |

### 11.1 Dynamic Skill Creation & `gemini.md` Evolution Protocol
Whenever an AI agent identifies a procedure or workflow that is performed repeatedly across tasks:
1. **Update `gemini.md`:** Immediately append or update the relevant section in `gemini.md` (e.g. under Section 10 Common Developer & Agent Tasks) with step-by-step instructions.
2. **Create a Skill:** If the pattern is a reusable, multi-step engineering workflow (e.g. adding a new entity/DAO pair, configuring new background workers, or writing custom MapLibre layers), create a specialized `SKILL.md` file in the skills directory.
3. **Keep Knowledge Updated:** Always maintain `gemini.md` as a living source of truth for the codebase so subsequent agent sessions don't repeat discovery steps.

