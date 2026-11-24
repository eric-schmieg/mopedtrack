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
| **Database** | Room Database | Version 4 (with schema migrations 1->2, 2->3, 3->4), KSP codegen |
| **Preferences** | DataStore Preferences | `SettingsDataStore` |
| **Networking** | Retrofit 2 + Moshi | Moshi KSP codegen, OkHttp, Overpass API with multi-endpoint fallback |
| **Background Processing** | WorkManager | `CoroutineWorker` (`GpxBulkImportWorker`, `GpxRescanWorker`) |
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
│   ├── local/                           # Room Database, Entities, DAOs & Migrations
│   │   ├── AppDatabase.kt               # Room database definition (Entities: Region, RoadSegment, ImportedGpx, DebugTrace)
│   │   ├── dao/                         # RegionDao, RoadSegmentDao, ImportedGpxFileDao, DebugTraceDao
│   │   └── entity/                      # RegionEntity, RoadSegmentEntity, ImportedGpxFileEntity, DebugTraceEntity
│   ├── preferences/                     # Settings DataStore implementation
│   │   └── SettingsDataStore.kt         # Preferences for match radius, threshold, layer visibility
│   ├── remote/                          # Remote Data Sources
│   │   ├── OsmApi.kt                    # Nominatim search API & Overpass query API interface
│   │   └── RetrofitClient.kt            # Retrofit setup with fallback Overpass endpoints
│   ├── repository/                      # Repository Implementation
│   │   └── MopedRepositoryImpl.kt       # Handles OSM data fetching, segment pairing, DB operations, settings
│   └── worker/                          # Background Workers
│       ├── GpxBulkImportWorker.kt       # Scans folder tree for .gpx, parses, matches, and persists
│       └── GpxRescanWorker.kt           # Rescans all GPX files against segments when parameters change
│
├── domain/                              # Domain Layer
│   ├── engine/                          # Core Algorithmic Engine
│   │   └── SegmentMatcherEngine.kt      # Interpolation, TraceSpatialIndex, segment coverage evaluation
│   ├── gpx/                             # GPX Parsing
│   │   └── GpxParser.kt                 # XML PullParser for GPX track points
│   ├── model/                           # Domain Data Models
│   │   ├── Region.kt                    # Domain representation of a Region
│   │   └── RoadSegment.kt               # Domain representation of a Road Segment
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
│   └── settings/                        # App Settings Screen
│       ├── SettingsScreen.kt            # Adjust match radius, coverage threshold, debug options
│       └── SettingsViewModel.kt         # DataStore settings bindings & rescan triggers
│
└── utils/                               # Utilities & Math Helpers
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
1. **Interpolation:** Segments are discretized into points spaced 5 meters apart (`interpolateSegment`).
2. **Spatial Grid (`TraceSpatialIndex`):** Trace segments are indexed in a 2D spatial grid cell structure (`cellSize = 0.005` degrees, ~550m).
3. **Bounding Box Overlap:** Candidate trace segments are rapidly retrieved via spatial grid cells.
4. **Coverage Calculation:**
   - Computes the percentage of sub-points on the segment within `matchRadiusMeters` of any trace segment.
   - **Dead-End Logic:** Lower coverage threshold (max of `50%` or `threshold - 15%`) plus requirement that either the start or end of the segment was hit.
   - **Regular Segment Logic:** Fully completed if coverage >= `coverageThresholdPercent` (default 80%). If coverage is between 70% and 80%, completed if both start and end 30% regions are covered.

---

## 5. Navigation & Screen Flow

The application uses **Jetpack Navigation 3** with explicit route keys (`MopedRoute`):
- `MopedRoute.Home`: Main map interface showing road segments, regions, GPX traces, and quick settings toggles.
- `MopedRoute.ManageRegions`: Search OSM for regions (cities, counties), download road networks, and view installed regions.
- `MopedRoute.ImportGpx`: Pick individual `.gpx` files or select folders for background WorkManager import.
- `MopedRoute.Settings`: Configure match radius (meters), coverage threshold (%), and toggle debug overlays.

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

### Unit Test Suites:
- `MopedRepositoryImplTest`: Tests OSM downloading, region deletion, and segment pairing.
- `SegmentMatcherEngineTest`: Tests trace matching, dead-end relaxation, and interpolation.
- `ManageRegionsViewModelTest`: Tests search and import ViewModel state handling.
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

### `imported_gpx_files` (`ImportedGpxFileEntity`)
- Primary Key: `fileHash` (SHA-256 string).
- Fields: `fileName`, `importedAt`, `trackPointCount`, `segmentsCompletedCount`, `encodedTrace`, `isVisibleOnMap`.

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
- MapLibre layers (`region-bounds-layer`, `road-segments-completed`, `road-segments-uncompleted`, `gpx-traces-layer`, `debug-overlay-layer`) update dynamically via `GeoJsonSource.setGeoJson()`.
- Debug overlay draws corridor polygons calculated by `createCorridorPolygon()` around road geometries to visualize coverage corridors.

---

## 10. Common Developer & Agent Tasks

### 10.1 Adding a New Preference / Setting
1. Add Key in `data/preferences/SettingsDataStore.kt`.
2. Expose `Flow` and suspend setter function in `SettingsDataStore` & `MopedRepository`.
3. Wire through `MopedRepositoryImpl`, `SettingsViewModel`, and Compose UI in `SettingsScreen.kt`.

### 10.2 Adding a Room Migration
1. Increment `version` in `@Database` annotation inside `AppDatabase.kt`.
2. Define a migration object `MIGRATION_X_Y = object : Migration(X, Y) { override fun migrate(db: SupportSQLiteDatabase) { ... } }`.
3. Add migration to `.addMigrations(...)` in `AppDatabase.getDatabase()`.

### 10.3 Overpass API Resilience
If Overpass requests fail due to rate limiting (HTTP 429) or timeouts:
- The app rotates through fallback endpoints in `OVERPASS_ENDPOINTS` (`overpass-api.de`, `kumi.systems`, `private.coffee`, `nchc.org.tw`).
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

