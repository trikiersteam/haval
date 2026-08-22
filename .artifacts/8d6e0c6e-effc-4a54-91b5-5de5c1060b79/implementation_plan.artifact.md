# Refactoring CarConstants and Externalizing DockKeys

The goal is to move the `DockKeys` object from `DockControls.kt` to `CarConstants.kt`, clean up `CarConstants.kt` to be a valid Kotlin object with constants, and ensure all required keys are present and correctly mapped.

## Proposed Changes

### [Component: Data Constants]

#### [MODIFY] [CarConstants.kt](file:///Users/rodrigo/StudioProjects/haval/app/src/main/java/br.com.redesurftank.havaldash/CarConstants.kt)
- Convert the `class CarConstants` into an `object CarConstants`.
- Transform the existing "enum-like" entries into proper `const val` declarations.
- Add the `DockKeys` object to this file.
- Ensure all keys used in `DockKeys` (from `DockControls.kt`) are represented as constants in `CarConstants`.
- Map `DockKeys` constants to the corresponding `CarConstants` values to avoid duplication of string literals.

#### [MODIFY] [DockControls.kt](file:///Users/rodrigo/StudioProjects/haval/app/src/main/java/br.com.redesurftank.havaldash/data/DockControls.kt)
- Remove the `object DockKeys` declaration.
- Add the necessary import for `br.com.redesurftank.havaldash.DockKeys` (or `br.com.redesurftank.havaldash.CarConstants.DockKeys`).

## Verification Plan

### Automated Tests
- Run a build to ensure no compilation errors due to missing constants or incorrect imports.

### Manual Verification
- Verify that `CarConstants.kt` is syntactically correct.
- Verify that all constants previously in `DockControls.kt` are now accessible via the new location.
