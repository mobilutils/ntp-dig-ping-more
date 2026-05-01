BulkActionsViewModel.kt
  - Added outputFilePath field to BulkUiState
  - onFileSelected() now auto-validates the output-file path after parsing (validates writable, stores expanded path in validatedOutputFile)
  - On parse failure, shows an error validation message

BulkActionsScreen.kt
  - Config summary card now shows: filename — N command(s) · timeout · output-file: <expanded_path>
  - Removed the "Validate Config" button entirely
  - Validation message card still appears automatically after file selection (success/info/error)

All unit tests pass.

