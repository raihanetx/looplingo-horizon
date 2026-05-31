# Cursor Rules for Horizon Loop

## Quick Reference

## CORE PRINCIPLES

### OPTIMIZATION IS #1 PRIORITY
- Performance (speed, memory)
- Developer experience (readable, maintainable)
- User experience (smooth, fast)
- Build time (modular, cached)
- App size (small, efficient)

### PRODUCTION-READY FROM DAY ONE
- No prototypes
- Every commit is deployable
- Every code path handles errors
- Every UI element is accessible

### JUSTIFIED DECISIONS
Before implementing ANYTHING:
- WHY this approach?
- WHY this technology?
- WHY this pattern?
- What are trade-offs?
- What is performance impact?

### REQUIREMENT-DRIVEN
- Understand EXACTLY what's needed
- Clarify BEFORE implementing
- Confirm approach if unclear
- Never assume - verify

---

## File Limits
- MAX 250 lines per .kt file
- Split larger files immediately

### Architecture
- Screen → ViewModel → UiState → Repository
- StateFlow for state
- collectAsStateWithLifecycle() in Composables

### Testing
- EVERY ViewModel needs unit tests
- Use MockK + Truth
- Test initial state, success, error, edge cases

### Accessibility
- ALL buttons need contentDescription
- ALL icons need contentDescription
- ALL inputs need contentDescription

### Error Handling
- Use ErrorState component
- Provide retry functionality
- Never crash

### Code Quality
- Run detekt before commit
- No magic numbers
- Max 60 lines per function
- Max 8 parameters

### Naming
- Files: PascalCase.kt
- Functions: camelCase
- Composables: PascalCase()
- Tests: `backticks with spaces`

### Import Order
1. Android/AndroidX
2. Project imports
3. Third-party

### Composable Rules
- State hoisting (pass state in, events out)
- ViewModel at screen level only
- No business logic in Composables

### Commit Format
```
<type>(<scope>): <description>
feat/fix/test/refactor/docs/chore
```

### Before Commit Checklist
- [ ] All files ≤ 250 lines
- [ ] Unit tests for ViewModels
- [ ] Accessibility (content descriptions)
- [ ] Error handling with retry
- [ ] detekt passes
- [ ] Architecture follows MVVM
