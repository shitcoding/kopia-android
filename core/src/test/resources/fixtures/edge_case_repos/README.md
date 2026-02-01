# Edge Case Test Repositories

These repositories are created for E2E testing of the KopiaKt Android app.

## edge_case_repo

A Kopia repository created with Go Kopia containing various edge case files:

### File Categories:
- **Unicode filenames**: Chinese (中文文件.txt), Japanese (日本語ファイル.txt), Korean (한국어파일.txt), Greek
- **Special characters**: #, $, %, &, @, brackets, quotes, parentheses
- **Long filenames**: 200+ character names
- **Hidden files**: .hidden_file, .hidden_dir
- **Emoji filenames**: 🎉party🎊.txt
- **Empty files/directories**
- **Binary files**: all_zeros_1kb.bin, sequential_bytes.bin, large_5mb.bin
- **Deeply nested directories**: 15 levels deep

### Repository Details:
- Password: `test123`
- Created with Go Kopia
- Contains 76 files total
- Total size: ~5 MB

## v1_test_repo

A Kopia repository using V1 index format for backward compatibility testing.

### Repository Details:
- Password: `test123`
- Uses V1 index format
