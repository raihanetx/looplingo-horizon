# Horizon Loop - Architecture

## Overview

Horizon Loop is an Android application for language learning through video content. It uses a modern Android architecture with Jetpack Compose, MVVM, and Clean Architecture principles.

## Tech Stack

| Component | Technology |
|-----------|------------|
| UI | Jetpack Compose + Material 3 |
| Architecture | MVVM + Clean Architecture |
| DI | Hilt |
| Database | Room |
| Navigation | Compose Navigation |
| Media | Media3 (ExoPlayer) |
| Networking | OkHttp + Gson |
| Async | Kotlin Coroutines + Flow |
| Testing | JUnit + MockK + Truth |
| Code Quality | Detekt + Android Lint |

## Project Structure

```
app/src/main/java/com/looplingo/horizon/
├── core/                    # Core utilities
│   ├── TimeUtils.kt
│   └── SecurePrefs.kt
├── data/                    # Data layer
│   ├── local/              # Local data source
│   │   ├── AppDatabase.kt
│   │   ├── dao/            # Data Access Objects
│   │   └── entity/         # Room entities
│   ├── remote/             # Remote data source
│   │   └── GroqApiClient.kt
│   └── repository/         # Repository implementations
├── di/                      # Dependency injection
│   └── DatabaseModule.kt
├── domain/                  # Domain layer
│   ├── audio/             # Audio playback
│   │   └── service/
│   └── model/             # Domain models
└── ui/                      # Presentation layer
    ├── home/              # Home screen
    │   ├── HomeScreen.kt
    │   ├── HomeViewModel.kt
    │   ├── HomeUiState.kt
    │   └── components/
    ├── player/            # Player screen
    │   ├── PlayerScreen.kt
    │   ├── PlayerViewModel.kt
    │   ├── PlayerUiState.kt
    │   └── components/
    ├── loop/              # Loop feature
    │   ├── LoopPanel.kt
    │   ├── LoopViewModel.kt
    │   ├── LoopUiState.kt
    │   └── components/
    ├── note/              # Note feature
    │   ├── NotePanel.kt
    │   ├── NoteViewModel.kt
    │   ├── NoteUiState.kt
    │   └── components/
    ├── dialogue/          # Dialogue feature
    │   ├── DialoguePanel.kt
    │   ├── DialogueViewModel.kt
    │   ├── DialogueUiState.kt
    │   └── components/
    ├── navigation/        # Navigation
    │   └── AppNavGraph.kt
    ├── theme/             # Theme
    │   ├── Color.kt
    │   ├── Type.kt
    │   └── HorizonTheme.kt
    ├── components/        # Shared components
    │   └── ErrorState.kt
    ├── common/            # Common utilities
    │   ├── WaveformSeekBar.kt
    │   └── ProcessLogger.kt
    └── MainActivity.kt
```

## Architecture Patterns

### MVVM (Model-View-ViewModel)

Each feature follows the MVVM pattern:

```
┌─────────────┐     ┌─────────────┐     ┌─────────────┐
│   Screen    │────▶│  ViewModel  │────▶│  Repository │
│ (Composable)│     │  (UiState)  │     │  (Data)     │
└─────────────┘     └─────────────┘     └─────────────┘
```

- **Screen**: Composable function that observes UiState
- **ViewModel**: Manages UiState, handles business logic
- **Repository**: Abstracts data sources (Room, Network)

### UiState Pattern

Each feature has a dedicated UiState data class:

```kotlin
data class HomeUiState(
    val videos: List<VideoEntity> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null
)
```

ViewModels expose StateFlow<UiState>:

```kotlin
private val _uiState = MutableStateFlow(HomeUiState())
val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()
```

## Data Flow

```
┌──────────────────────────────────────────────────────────────┐
│                        UI Layer                              │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐         │
│  │ HomeScreen  │  │PlayerScreen │  │  LoopPanel  │         │
│  └──────┬──────┘  └──────┬──────┘  └──────┬──────┘         │
│         │                │                │                 │
│         ▼                ▼                ▼                 │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐         │
│  │HomeViewModel│  │PlayerViewModel│ │LoopViewModel│         │
│  └──────┬──────┘  └──────┬──────┘  └──────┬──────┘         │
└─────────┼────────────────┼────────────────┼─────────────────┘
          │                │                │
          ▼                ▼                ▼
┌──────────────────────────────────────────────────────────────┐
│                      Domain Layer                            │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐         │
│  │VideoRepository│ │PlaybackRepository│ │SavedTimestampDao│ │
│  └──────┬──────┘  └──────┬──────┘  └──────┬──────┘         │
└─────────┼────────────────┼────────────────┼─────────────────┘
          │                │                │
          ▼                ▼                ▼
┌──────────────────────────────────────────────────────────────┐
│                       Data Layer                             │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐         │
│  │   Room DB   │  │  MediaStore │  │  Groq API   │         │
│  └─────────────┘  └─────────────┘  └─────────────┘         │
└──────────────────────────────────────────────────────────────┘
```

## Dependency Injection

Hilt manages dependencies:

```kotlin
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase {
        return Room.databaseBuilder(...)
            .addMigrations(...)
            .build()
    }

    @Provides
    fun provideVideoDao(database: AppDatabase): VideoDao {
        return database.videoDao()
    }
}
```

ViewModels are injected:

```kotlin
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val videoRepository: VideoRepository,
    private val playbackRepository: PlaybackRepository
) : ViewModel()
```

## Navigation

Compose Navigation with type-safe routes:

```kotlin
object Routes {
    const val HOME = "home"
    const val PLAYER = "player/{videoPath}/{videoTitle}"
}

@Composable
fun AppNavGraph(navController: NavHostController) {
    NavHost(navController = navController, startDestination = Routes.HOME) {
        composable(Routes.HOME) { HomeScreen(...) }
        composable(Routes.PLAYER) { PlayerScreen(...) }
    }
}
```

## Testing Strategy

### Unit Tests
- ViewModels tested with MockK and Truth
- Repositories tested with fake implementations
- Domain models tested for correctness

### UI Tests
- Compose screens tested with ComposeTestRule
- Accessibility tests with semantics
- Integration tests for navigation

### Test Structure
```
app/src/test/           # Unit tests
app/src/androidTest/    # Instrumented tests
```

## Code Quality

### Detekt
- Static analysis for Kotlin
- Custom rules in `config/detekt/detekt.yml`
- Integrated into CI/CD pipeline

### Android Lint
- Built-in Android checks
- Custom lint rules for Compose

## Performance

### Baseline Profiles
- Critical user journeys optimized
- Startup time minimized
- Scroll performance optimized

### Memory Management
- LeakCanary in debug builds
- Proper lifecycle management
- Coroutine scope cancellation

## Security

### ProGuard
- Code obfuscation in release
- Compose-specific rules
- Entity preservation

### Data Security
- Encrypted SharedPreferences for API keys
- No hardcoded secrets
- Secure network communication

## CI/CD

GitHub Actions workflow:

```yaml
name: Android CI
on:
  push:
    branches: [main, develop]
  pull_request:
    branches: [main, develop]

jobs:
  build:
    steps:
      - Checkout
      - Setup JDK 17
      - Cache Gradle
      - Run lint
      - Run tests
      - Build debug APK
```

## Future Considerations

1. **Multi-module**: Split into feature modules
2. **KMP**: Kotlin Multiplatform for iOS
3. **Compose Multiplatform**: Shared UI
4. **Baseline Profiles**: AOT compilation
5. **Macrobenchmark**: Performance testing
