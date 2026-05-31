# AGENTS.md - Rules for AI Agents

## Project: Horizon Loop

This document defines the rules, conventions, and standards that ALL AI agents MUST follow when working on this codebase.

---

## CORE PRINCIPLES (Non-Negotiable)

### 1. OPTIMIZATION IS THE PRIMARY GOAL
```
EVERY decision must optimize for:
- Performance (runtime speed, memory usage)
- Developer experience (readability, maintainability)
- User experience (smooth UI, fast load times)
- Build time (modularization, caching)
- App size (ProGuard, resource optimization)
```

### 2. PRODUCTION-READY FROM DAY ONE
```
NO prototypes, NO "we'll fix it later"
- Every feature ships production-quality
- Every commit is deployable
- Every code path handles errors
- Every UI element is accessible
```

### 3. JUSTIFIED DECISIONS
```
BEFORE implementing ANYTHING, document:
- WHY this approach over alternatives
- WHY this technology/library
- WHY this architecture pattern
- WHAT are the trade-offs
- WHAT are the performance implications
```

### 4. REQUIREMENT-DRIVEN DEVELOPMENT
```
BEFORE writing code:
- Understand EXACTLY what user needs
- Clarify ambiguities BEFORE implementation
- Confirm approach with user if unclear
- Never assume - always verify
```

---

## DECISION DOCUMENTATION TEMPLATE

For every significant decision, document:

```markdown
## Decision: [Title]

### Context
What problem are we solving?

### Options Considered
1. Option A - [brief description]
2. Option B - [brief description]
3. Option C - [brief description]

### Decision
We chose [Option X] because:
- Reason 1 (performance/maintainability/etc)
- Reason 2 (ecosystem support/etc)
- Reason 3 (team familiarity/etc)

### Trade-offs
- What we gain: [benefits]
- What we lose: [costs]
- Mitigation: [how we address costs]

### Performance Impact
- Memory: [impact]
- CPU: [impact]
- App Size: [impact]
- Build Time: [impact]
```

---

## EXAMPLE DECISIONS (Reference)

### Why Jetpack Compose over XML?
```
Decision: Jetpack Compose
Reasons:
- 50% less code than XML
- Declarative UI = easier to reason about
- Better state management
- Official Google recommendation
- Better testing support
- No XML inflation overhead

Trade-offs:
- Gain: Modern UI toolkit, better performance
- Lose: Some legacy library compatibility
- Mitigation: AndroidView wrapper for legacy views

Performance:
- Memory: Similar or better
- CPU: Better (no XML parsing)
- App Size: Smaller (no XML resources)
- Build Time: Slightly longer (Compose compiler)
```

### Why MVVM over MVI?
```
Decision: MVVM + UiState
Reasons:
- Simpler mental model
- Less boilerplate
- Better for small-to-medium teams
- Official Android recommendation
- Easier to test

Trade-offs:
- Gain: Simpler code, faster development
- Lose: Unidirectional data flow guarantees
- Mitigation: UiState pattern provides similar benefits

Performance:
- Memory: Similar
- CPU: Similar
- App Size: Smaller (less code)
- Build Time: Faster (less codegen)
```

### Why MockK over Mockito?
```
Decision: MockK
Reasons:
- Built for Kotlin
- Better coroutine support
- Extension functions support
- More idiomatic Kotlin DSL
- Better null safety

Trade-offs:
- Gain: Kotlin-native mocking
- Lose: Larger library size
- Mitigation: Test-only dependency

Performance:
- Memory: Test-only (no production impact)
- CPU: Test-only
- App Size: No impact (test dependency)
- Build Time: Minimal impact
```

---

## MANDATORY RULES (Cannot be ignored)

### 1. File Size Limit
```
MAXIMUM 250 LINES PER FILE
```
- If a file exceeds 250 lines, split it into multiple files
- Larger files ONLY with explicit user permission
- This applies to ALL .kt files

### 2. Architecture Pattern
```
MVVM + UiState + Compose
```
- Every feature MUST follow: Screen → ViewModel → UiState → Repository
- ViewModels expose `StateFlow<UiState>`
- Screens observe with `collectAsStateWithLifecycle()`
- NO business logic in Composables

### 3. File Structure (Feature-wise)
```
ui/
├── feature/
│   ├── FeatureScreen.kt      # Composable UI
│   ├── FeatureViewModel.kt   # State management
│   ├── FeatureUiState.kt     # Data class
│   └── components/           # Sub-components
```

### 4. Testing Requirements
```
EVERY ViewModel MUST have unit tests
```
- Test file location: `app/src/test/java/.../FeatureViewModelTest.kt`
- Use MockK for mocking
- Use Truth for assertions
- Test: initial state, success paths, error paths, edge cases

### 5. Code Quality
```
Run detekt before committing
```
- No magic numbers (use constants)
- Max function length: 60 lines
- Max class length: 600 lines
- Max parameters: 8

### 6. Accessibility
```
ALL interactive elements MUST have content descriptions
```
- Buttons: `contentDescription = "Action description"`
- Icons: `contentDescription = "Icon name"`
- Inputs: `contentDescription = "Field purpose"`

### 7. Error Handling
```
ALL ViewModels MUST handle errors gracefully
```
- Use `ErrorState` component for error display
- Provide retry functionality
- Never crash - catch and display

---

## PRODUCTION-READY CHECKLIST

Before ANY commit, verify:

```markdown
- [ ] All files ≤ 250 lines
- [ ] Unit tests for ViewModels
- [ ] Accessibility (content descriptions)
- [ ] Error handling with retry
- [ ] No hardcoded strings (use resources)
- [ ] No memory leaks (proper lifecycle)
- [ ] ProGuard rules updated if needed
- [ ] detekt passes
- [ ] Architecture follows MVVM pattern
```

---

## TECH STACK (Do not change without user approval)

| Component | Technology |
|-----------|------------|
| UI | Jetpack Compose + Material 3 |
| Architecture | MVVM + UiState |
| DI | Hilt |
| Database | Room |
| Navigation | Compose Navigation |
| Media | Media3 (ExoPlayer) |
| Async | Kotlin Coroutines + Flow |
| Testing | JUnit + MockK + Truth |
| Code Quality | Detekt |

---

## NAMING CONVENTIONS

### Files
- Composables: `PascalCase.kt` (e.g., `HomeScreen.kt`)
- ViewModels: `FeatureViewModel.kt`
- UiStates: `FeatureUiState.kt`
- Tests: `FeatureViewModelTest.kt`

### Functions
- Composables: `PascalCase` (e.g., `HomeScreen()`)
- Regular functions: `camelCase` (e.g., `loadVideos()`)
- Test functions: `backticks with spaces` (e.g., `` `initial state is empty` ``)

### Variables
- Private: `_uiState` (MutableStateFlow)
- Public: `uiState` (StateFlow)
- Constants: `UPPER_SNAKE_CASE`

---

## IMPORT ORDER

```kotlin
// 1. Android/AndroidX
import android.os.Bundle
import androidx.compose.runtime.*

// 2. Project imports
import com.looplingo.horizon.ui.theme.HorizonTheme

// 3. Third-party
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import timber.log.Timber
```

---

## COMPOSABLE RULES

### State Hoisting
```kotlin
// CORRECT - State hoisted
@Composable
fun MyScreen(
    items: List<Item>,           // State passed in
    onItemClick: (Item) -> Unit  // Event passed out
)

// WRONG - State inside
@Composable
fun MyScreen() {
    var items by remember { mutableStateOf(emptyList()) }  // BAD
}
```

### ViewModel Access
```kotlin
// CORRECT - ViewModel at screen level
@Composable
fun HomeScreen(
    viewModel: HomeViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    // Pass state down to children
}

// WRONG - ViewModel in child composable
@Composable
fun VideoItem() {
    val viewModel: HomeViewModel = hiltViewModel()  // BAD
}
```

---

## TESTING PATTERNS

### ViewModel Test Template
```kotlin
class FeatureViewModelTest {
    private lateinit var repository: FeatureRepository
    private lateinit var viewModel: FeatureViewModel
    private lateinit var testDispatcher: TestDispatcher

    @Before
    fun setUp() {
        testDispatcher = UnconfinedTestDispatcher()
        Dispatchers.setMain(testDispatcher)
        repository = mockk(relaxed = true)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial state is empty`() {
        viewModel = FeatureViewModel(repository)
        assertThat(viewModel.uiState.value.items).isEmpty()
    }
}
```

---

## CI/CD PIPELINE

Every push triggers:
1. Lint check
2. Unit tests
3. Debug build
4. Release build (main branch only)

---

## ERROR MESSAGES

Use user-friendly messages:
```kotlin
// CORRECT
_uiState.update { it.copy(error = "Failed to load videos. Tap to retry.") }

// WRONG
_uiState.update { it.copy(error = e.message) }  // Technical
_uiState.update { it.copy(error = "Error occurred") }  // Vague
```

---

## GIT COMMIT MESSAGES

Format:
```
<type>(<scope>): <description>

Types:
- feat: New feature
- fix: Bug fix
- test: Adding tests
- refactor: Code restructuring
- docs: Documentation
- chore: Build/tooling

Example:
feat(home): Add video search functionality
test(loop): Add unit tests for LoopViewModel
```

---

## WHEN IN DOUBT

1. Check existing code for patterns
2. Follow the architecture documentation (`docs/ARCHITECTURE.md`)
3. Ask the user before making architectural changes
4. When unsure, create a plan first

---

## LAST UPDATED

Date: 2026-01-31
Version: 1.0
Status: ACTIVE - Must be followed by all agents
