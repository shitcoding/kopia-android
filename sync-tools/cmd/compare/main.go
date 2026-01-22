// Package main provides a tool to compare two API snapshots and detect
// breaking changes between Go Kopia versions.
package main

import (
	"encoding/json"
	"flag"
	"fmt"
	"os"
	"sort"
	"strings"
)

// AnalysisResult mirrors the structure from the analyze tool.
type AnalysisResult struct {
	Version    string                 `json:"version"`
	Timestamp  string                 `json:"timestamp"`
	Packages   map[string]PackageInfo `json:"packages"`
	Statistics Statistics             `json:"statistics"`
}

type Statistics struct {
	TotalPackages   int `json:"totalPackages"`
	TotalInterfaces int `json:"totalInterfaces"`
	TotalStructs    int `json:"totalStructs"`
	TotalFunctions  int `json:"totalFunctions"`
	TotalConstants  int `json:"totalConstants"`
}

type PackageInfo struct {
	Name       string          `json:"name"`
	Path       string          `json:"path"`
	Interfaces []InterfaceInfo `json:"interfaces,omitempty"`
	Structs    []StructInfo    `json:"structs,omitempty"`
	Functions  []FunctionInfo  `json:"functions,omitempty"`
	Constants  []ConstantInfo  `json:"constants,omitempty"`
}

type InterfaceInfo struct {
	Name     string       `json:"name"`
	Doc      string       `json:"doc,omitempty"`
	Methods  []MethodInfo `json:"methods"`
	Embedded []string     `json:"embedded,omitempty"`
}

type MethodInfo struct {
	Name    string      `json:"name"`
	Doc     string      `json:"doc,omitempty"`
	Params  []ParamInfo `json:"params,omitempty"`
	Returns []ParamInfo `json:"returns,omitempty"`
}

type ParamInfo struct {
	Name string `json:"name,omitempty"`
	Type string `json:"type"`
}

type StructInfo struct {
	Name     string      `json:"name"`
	Doc      string      `json:"doc,omitempty"`
	Fields   []FieldInfo `json:"fields,omitempty"`
	Embedded []string    `json:"embedded,omitempty"`
}

type FieldInfo struct {
	Name string `json:"name"`
	Type string `json:"type"`
	Tag  string `json:"tag,omitempty"`
	Doc  string `json:"doc,omitempty"`
}

type FunctionInfo struct {
	Name     string      `json:"name"`
	Doc      string      `json:"doc,omitempty"`
	Receiver string      `json:"receiver,omitempty"`
	Params   []ParamInfo `json:"params,omitempty"`
	Returns  []ParamInfo `json:"returns,omitempty"`
}

type ConstantInfo struct {
	Name  string `json:"name"`
	Type  string `json:"type,omitempty"`
	Value string `json:"value,omitempty"`
	Doc   string `json:"doc,omitempty"`
}

// ChangeType categorizes the kind of change detected.
type ChangeType string

const (
	ChangeAdded    ChangeType = "added"
	ChangeRemoved  ChangeType = "removed"
	ChangeModified ChangeType = "modified"
)

// Severity indicates how breaking a change is.
type Severity string

const (
	SeverityBreaking Severity = "breaking"
	SeverityWarning  Severity = "warning"
	SeverityInfo     Severity = "info"
)

// Change represents a single API change.
type Change struct {
	Package     string     `json:"package"`
	Type        string     `json:"type"` // interface, struct, function, constant
	Name        string     `json:"name"`
	ChangeType  ChangeType `json:"changeType"`
	Severity    Severity   `json:"severity"`
	Description string     `json:"description"`
	OldValue    string     `json:"oldValue,omitempty"`
	NewValue    string     `json:"newValue,omitempty"`
}

// ComparisonResult contains all detected changes.
type ComparisonResult struct {
	OldVersion     string   `json:"oldVersion"`
	NewVersion     string   `json:"newVersion"`
	Changes        []Change `json:"changes"`
	BreakingCount  int      `json:"breakingCount"`
	WarningCount   int      `json:"warningCount"`
	InfoCount      int      `json:"infoCount"`
	KotlinImpacted []string `json:"kotlinImpacted,omitempty"`
}

// Comparer compares two API snapshots.
type Comparer struct {
	old     AnalysisResult
	new     AnalysisResult
	changes []Change
}

// NewComparer creates a new comparer.
func NewComparer(old, new AnalysisResult) *Comparer {
	return &Comparer{
		old: old,
		new: new,
	}
}

// Compare performs the comparison and returns all changes.
func (c *Comparer) Compare() ComparisonResult {
	// Compare packages
	allPkgs := make(map[string]bool)
	for pkg := range c.old.Packages {
		allPkgs[pkg] = true
	}
	for pkg := range c.new.Packages {
		allPkgs[pkg] = true
	}

	for pkg := range allPkgs {
		oldPkg, oldExists := c.old.Packages[pkg]
		newPkg, newExists := c.new.Packages[pkg]

		if !oldExists {
			c.addChange(Change{
				Package:     pkg,
				Type:        "package",
				Name:        pkg,
				ChangeType:  ChangeAdded,
				Severity:    SeverityInfo,
				Description: fmt.Sprintf("New package added: %s", pkg),
			})
			continue
		}
		if !newExists {
			c.addChange(Change{
				Package:     pkg,
				Type:        "package",
				Name:        pkg,
				ChangeType:  ChangeRemoved,
				Severity:    SeverityBreaking,
				Description: fmt.Sprintf("Package removed: %s", pkg),
			})
			continue
		}

		c.comparePackages(pkg, oldPkg, newPkg)
	}

	// Sort changes by severity, then package, then name
	sort.Slice(c.changes, func(i, j int) bool {
		if c.changes[i].Severity != c.changes[j].Severity {
			return severityOrder(c.changes[i].Severity) < severityOrder(c.changes[j].Severity)
		}
		if c.changes[i].Package != c.changes[j].Package {
			return c.changes[i].Package < c.changes[j].Package
		}
		return c.changes[i].Name < c.changes[j].Name
	})

	result := ComparisonResult{
		OldVersion: c.old.Timestamp,
		NewVersion: c.new.Timestamp,
		Changes:    c.changes,
	}

	for _, ch := range c.changes {
		switch ch.Severity {
		case SeverityBreaking:
			result.BreakingCount++
		case SeverityWarning:
			result.WarningCount++
		case SeverityInfo:
			result.InfoCount++
		}
	}

	// Identify Kotlin files that may be impacted
	result.KotlinImpacted = c.identifyKotlinImpact()

	return result
}

func severityOrder(s Severity) int {
	switch s {
	case SeverityBreaking:
		return 0
	case SeverityWarning:
		return 1
	case SeverityInfo:
		return 2
	default:
		return 3
	}
}

func (c *Comparer) addChange(ch Change) {
	c.changes = append(c.changes, ch)
}

func (c *Comparer) comparePackages(pkg string, old, new PackageInfo) {
	c.compareInterfaces(pkg, old.Interfaces, new.Interfaces)
	c.compareStructs(pkg, old.Structs, new.Structs)
	c.compareFunctions(pkg, old.Functions, new.Functions)
	c.compareConstants(pkg, old.Constants, new.Constants)
}

func (c *Comparer) compareInterfaces(pkg string, old, new []InterfaceInfo) {
	oldMap := make(map[string]InterfaceInfo)
	for _, i := range old {
		oldMap[i.Name] = i
	}
	newMap := make(map[string]InterfaceInfo)
	for _, i := range new {
		newMap[i.Name] = i
	}

	for name, oldIface := range oldMap {
		newIface, exists := newMap[name]
		if !exists {
			c.addChange(Change{
				Package:     pkg,
				Type:        "interface",
				Name:        name,
				ChangeType:  ChangeRemoved,
				Severity:    SeverityBreaking,
				Description: fmt.Sprintf("Interface removed: %s", name),
			})
			continue
		}
		c.compareInterfaceMethods(pkg, name, oldIface.Methods, newIface.Methods)
	}

	for name := range newMap {
		if _, exists := oldMap[name]; !exists {
			c.addChange(Change{
				Package:     pkg,
				Type:        "interface",
				Name:        name,
				ChangeType:  ChangeAdded,
				Severity:    SeverityInfo,
				Description: fmt.Sprintf("Interface added: %s", name),
			})
		}
	}
}

func (c *Comparer) compareInterfaceMethods(pkg, ifaceName string, old, new []MethodInfo) {
	oldMap := make(map[string]MethodInfo)
	for _, m := range old {
		oldMap[m.Name] = m
	}
	newMap := make(map[string]MethodInfo)
	for _, m := range new {
		newMap[m.Name] = m
	}

	for name, oldMethod := range oldMap {
		newMethod, exists := newMap[name]
		if !exists {
			c.addChange(Change{
				Package:     pkg,
				Type:        "interface",
				Name:        fmt.Sprintf("%s.%s", ifaceName, name),
				ChangeType:  ChangeRemoved,
				Severity:    SeverityBreaking,
				Description: fmt.Sprintf("Method removed from interface %s: %s", ifaceName, name),
			})
			continue
		}

		// Compare method signatures
		oldSig := methodSignature(oldMethod)
		newSig := methodSignature(newMethod)
		if oldSig != newSig {
			c.addChange(Change{
				Package:     pkg,
				Type:        "interface",
				Name:        fmt.Sprintf("%s.%s", ifaceName, name),
				ChangeType:  ChangeModified,
				Severity:    SeverityBreaking,
				Description: fmt.Sprintf("Method signature changed in interface %s", ifaceName),
				OldValue:    oldSig,
				NewValue:    newSig,
			})
		}
	}

	for name := range newMap {
		if _, exists := oldMap[name]; !exists {
			c.addChange(Change{
				Package:     pkg,
				Type:        "interface",
				Name:        fmt.Sprintf("%s.%s", ifaceName, name),
				ChangeType:  ChangeAdded,
				Severity:    SeverityBreaking, // Adding to interface is breaking!
				Description: fmt.Sprintf("Method added to interface %s: %s (implementers must update)", ifaceName, name),
			})
		}
	}
}

func (c *Comparer) compareStructs(pkg string, old, new []StructInfo) {
	oldMap := make(map[string]StructInfo)
	for _, s := range old {
		oldMap[s.Name] = s
	}
	newMap := make(map[string]StructInfo)
	for _, s := range new {
		newMap[s.Name] = s
	}

	for name, oldStruct := range oldMap {
		newStruct, exists := newMap[name]
		if !exists {
			c.addChange(Change{
				Package:     pkg,
				Type:        "struct",
				Name:        name,
				ChangeType:  ChangeRemoved,
				Severity:    SeverityBreaking,
				Description: fmt.Sprintf("Struct removed: %s", name),
			})
			continue
		}
		c.compareStructFields(pkg, name, oldStruct.Fields, newStruct.Fields)
	}

	for name := range newMap {
		if _, exists := oldMap[name]; !exists {
			c.addChange(Change{
				Package:     pkg,
				Type:        "struct",
				Name:        name,
				ChangeType:  ChangeAdded,
				Severity:    SeverityInfo,
				Description: fmt.Sprintf("Struct added: %s", name),
			})
		}
	}
}

func (c *Comparer) compareStructFields(pkg, structName string, old, new []FieldInfo) {
	oldMap := make(map[string]FieldInfo)
	for _, f := range old {
		oldMap[f.Name] = f
	}
	newMap := make(map[string]FieldInfo)
	for _, f := range new {
		newMap[f.Name] = f
	}

	for name, oldField := range oldMap {
		newField, exists := newMap[name]
		if !exists {
			c.addChange(Change{
				Package:     pkg,
				Type:        "struct",
				Name:        fmt.Sprintf("%s.%s", structName, name),
				ChangeType:  ChangeRemoved,
				Severity:    SeverityBreaking,
				Description: fmt.Sprintf("Field removed from struct %s: %s", structName, name),
			})
			continue
		}

		if oldField.Type != newField.Type {
			c.addChange(Change{
				Package:     pkg,
				Type:        "struct",
				Name:        fmt.Sprintf("%s.%s", structName, name),
				ChangeType:  ChangeModified,
				Severity:    SeverityBreaking,
				Description: fmt.Sprintf("Field type changed in struct %s", structName),
				OldValue:    oldField.Type,
				NewValue:    newField.Type,
			})
		}

		if oldField.Tag != newField.Tag {
			c.addChange(Change{
				Package:     pkg,
				Type:        "struct",
				Name:        fmt.Sprintf("%s.%s", structName, name),
				ChangeType:  ChangeModified,
				Severity:    SeverityWarning,
				Description: fmt.Sprintf("Field tag changed in struct %s (may affect serialization)", structName),
				OldValue:    oldField.Tag,
				NewValue:    newField.Tag,
			})
		}
	}

	for name := range newMap {
		if _, exists := oldMap[name]; !exists {
			c.addChange(Change{
				Package:     pkg,
				Type:        "struct",
				Name:        fmt.Sprintf("%s.%s", structName, name),
				ChangeType:  ChangeAdded,
				Severity:    SeverityWarning, // Adding fields can affect serialization
				Description: fmt.Sprintf("Field added to struct %s: %s (may affect serialization)", structName, name),
			})
		}
	}
}

func (c *Comparer) compareFunctions(pkg string, old, new []FunctionInfo) {
	// Create keys that include receiver for method comparison
	oldMap := make(map[string]FunctionInfo)
	for _, f := range old {
		key := f.Name
		if f.Receiver != "" {
			key = f.Receiver + "." + f.Name
		}
		oldMap[key] = f
	}
	newMap := make(map[string]FunctionInfo)
	for _, f := range new {
		key := f.Name
		if f.Receiver != "" {
			key = f.Receiver + "." + f.Name
		}
		newMap[key] = f
	}

	for name, oldFn := range oldMap {
		newFn, exists := newMap[name]
		if !exists {
			c.addChange(Change{
				Package:     pkg,
				Type:        "function",
				Name:        name,
				ChangeType:  ChangeRemoved,
				Severity:    SeverityBreaking,
				Description: fmt.Sprintf("Function removed: %s", name),
			})
			continue
		}

		oldSig := funcSignature(oldFn)
		newSig := funcSignature(newFn)
		if oldSig != newSig {
			c.addChange(Change{
				Package:     pkg,
				Type:        "function",
				Name:        name,
				ChangeType:  ChangeModified,
				Severity:    SeverityBreaking,
				Description: "Function signature changed",
				OldValue:    oldSig,
				NewValue:    newSig,
			})
		}
	}

	for name := range newMap {
		if _, exists := oldMap[name]; !exists {
			c.addChange(Change{
				Package:     pkg,
				Type:        "function",
				Name:        name,
				ChangeType:  ChangeAdded,
				Severity:    SeverityInfo,
				Description: fmt.Sprintf("Function added: %s", name),
			})
		}
	}
}

func (c *Comparer) compareConstants(pkg string, old, new []ConstantInfo) {
	oldMap := make(map[string]ConstantInfo)
	for _, const_ := range old {
		oldMap[const_.Name] = const_
	}
	newMap := make(map[string]ConstantInfo)
	for _, const_ := range new {
		newMap[const_.Name] = const_
	}

	for name, oldConst := range oldMap {
		newConst, exists := newMap[name]
		if !exists {
			c.addChange(Change{
				Package:     pkg,
				Type:        "constant",
				Name:        name,
				ChangeType:  ChangeRemoved,
				Severity:    SeverityBreaking,
				Description: fmt.Sprintf("Constant removed: %s", name),
			})
			continue
		}

		if oldConst.Value != newConst.Value {
			c.addChange(Change{
				Package:     pkg,
				Type:        "constant",
				Name:        name,
				ChangeType:  ChangeModified,
				Severity:    SeverityBreaking,
				Description: "Constant value changed",
				OldValue:    oldConst.Value,
				NewValue:    newConst.Value,
			})
		}
	}

	for name := range newMap {
		if _, exists := oldMap[name]; !exists {
			c.addChange(Change{
				Package:     pkg,
				Type:        "constant",
				Name:        name,
				ChangeType:  ChangeAdded,
				Severity:    SeverityInfo,
				Description: fmt.Sprintf("Constant added: %s", name),
			})
		}
	}
}

func methodSignature(m MethodInfo) string {
	var sb strings.Builder
	sb.WriteString(m.Name)
	sb.WriteString("(")
	for i, p := range m.Params {
		if i > 0 {
			sb.WriteString(", ")
		}
		sb.WriteString(p.Type)
	}
	sb.WriteString(")")
	if len(m.Returns) > 0 {
		sb.WriteString(" (")
		for i, r := range m.Returns {
			if i > 0 {
				sb.WriteString(", ")
			}
			sb.WriteString(r.Type)
		}
		sb.WriteString(")")
	}
	return sb.String()
}

func funcSignature(f FunctionInfo) string {
	var sb strings.Builder
	if f.Receiver != "" {
		sb.WriteString("(")
		sb.WriteString(f.Receiver)
		sb.WriteString(") ")
	}
	sb.WriteString(f.Name)
	sb.WriteString("(")
	for i, p := range f.Params {
		if i > 0 {
			sb.WriteString(", ")
		}
		sb.WriteString(p.Type)
	}
	sb.WriteString(")")
	if len(f.Returns) > 0 {
		sb.WriteString(" (")
		for i, r := range f.Returns {
			if i > 0 {
				sb.WriteString(", ")
			}
			sb.WriteString(r.Type)
		}
		sb.WriteString(")")
	}
	return sb.String()
}

// identifyKotlinImpact returns a list of Kotlin files likely impacted by changes.
func (c *Comparer) identifyKotlinImpact() []string {
	// Map Go packages to Kotlin files
	goToKotlin := map[string][]string{
		"repo/blob":                {"core/src/main/kotlin/org/kopiaKt/core/blob/"},
		"repo/blob/filesystem":     {"storage/src/main/kotlin/org/kopiaKt/storage/filesystem/"},
		"repo/blob/s3":             {"storage/src/main/kotlin/org/kopiaKt/storage/s3/"},
		"repo/blob/webdav":         {"storage/src/main/kotlin/org/kopiaKt/storage/webdav/"},
		"repo/blob/sftp":           {"storage/src/main/kotlin/org/kopiaKt/storage/sftp/"},
		"repo/content":             {"core/src/main/kotlin/org/kopiaKt/core/content/"},
		"repo/content/index":       {"core/src/main/kotlin/org/kopiaKt/core/content/index/"},
		"repo/encryption":          {"core/src/main/kotlin/org/kopiaKt/core/crypto/"},
		"repo/hashing":             {"core/src/main/kotlin/org/kopiaKt/core/crypto/"},
		"repo/compression":         {"core/src/main/kotlin/org/kopiaKt/core/compression/"},
		"repo/splitter":            {"core/src/main/kotlin/org/kopiaKt/core/splitter/"},
		"repo/object":              {"core/src/main/kotlin/org/kopiaKt/core/object/"},
		"repo/manifest":            {"core/src/main/kotlin/org/kopiaKt/core/manifest/"},
		"repo/format":              {"core/src/main/kotlin/org/kopiaKt/core/format/"},
		"repo":                     {"core/src/main/kotlin/org/kopiaKt/core/repo/"},
		"snapshot":                 {"snapshot/src/main/kotlin/org/kopiaKt/snapshot/"},
		"snapshot/policy":          {"snapshot/src/main/kotlin/org/kopiaKt/snapshot/policy/"},
		"snapshot/restore":         {"snapshot/src/main/kotlin/org/kopiaKt/snapshot/restore/"},
		"snapshot/snapshotfs":      {"snapshot/src/main/kotlin/org/kopiaKt/snapshot/snapshotfs/"},
		"snapshot/snapshotmaintenance": {"snapshot/src/main/kotlin/org/kopiaKt/snapshot/maintenance/"},
		"fs":                       {"snapshot/src/main/kotlin/org/kopiaKt/snapshot/fs/"},
		"fs/localfs":               {"snapshot/src/main/kotlin/org/kopiaKt/snapshot/fs/"},
	}

	impacted := make(map[string]bool)
	for _, ch := range c.changes {
		if ch.Severity == SeverityBreaking || ch.Severity == SeverityWarning {
			if paths, ok := goToKotlin[ch.Package]; ok {
				for _, p := range paths {
					impacted[p] = true
				}
			}
		}
	}

	var result []string
	for path := range impacted {
		result = append(result, path)
	}
	sort.Strings(result)
	return result
}

func main() {
	var (
		oldFile    = flag.String("old", "", "Path to old API snapshot JSON")
		newFile    = flag.String("new", "", "Path to new API snapshot JSON")
		outputFile = flag.String("output", "changes.json", "Output JSON file")
		pretty     = flag.Bool("pretty", true, "Pretty-print JSON output")
		markdown   = flag.Bool("markdown", false, "Also generate markdown report")
	)
	flag.Parse()

	if *oldFile == "" || *newFile == "" {
		fmt.Fprintln(os.Stderr, "Error: -old and -new flags are required")
		flag.Usage()
		os.Exit(1)
	}

	oldData, err := os.ReadFile(*oldFile)
	if err != nil {
		fmt.Fprintf(os.Stderr, "Error reading old file: %v\n", err)
		os.Exit(1)
	}

	newData, err := os.ReadFile(*newFile)
	if err != nil {
		fmt.Fprintf(os.Stderr, "Error reading new file: %v\n", err)
		os.Exit(1)
	}

	var old, new AnalysisResult
	if err := json.Unmarshal(oldData, &old); err != nil {
		fmt.Fprintf(os.Stderr, "Error parsing old JSON: %v\n", err)
		os.Exit(1)
	}
	if err := json.Unmarshal(newData, &new); err != nil {
		fmt.Fprintf(os.Stderr, "Error parsing new JSON: %v\n", err)
		os.Exit(1)
	}

	comparer := NewComparer(old, new)
	result := comparer.Compare()

	var data []byte
	if *pretty {
		data, err = json.MarshalIndent(result, "", "  ")
	} else {
		data, err = json.Marshal(result)
	}
	if err != nil {
		fmt.Fprintf(os.Stderr, "Error marshaling JSON: %v\n", err)
		os.Exit(1)
	}

	if err := os.WriteFile(*outputFile, data, 0644); err != nil {
		fmt.Fprintf(os.Stderr, "Error writing file: %v\n", err)
		os.Exit(1)
	}

	fmt.Printf("Changes written to %s\n", *outputFile)
	fmt.Printf("Summary:\n")
	fmt.Printf("  Breaking changes: %d\n", result.BreakingCount)
	fmt.Printf("  Warnings:         %d\n", result.WarningCount)
	fmt.Printf("  Info:             %d\n", result.InfoCount)

	if len(result.KotlinImpacted) > 0 {
		fmt.Printf("\nKotlin paths likely impacted:\n")
		for _, path := range result.KotlinImpacted {
			fmt.Printf("  - %s\n", path)
		}
	}

	if *markdown {
		mdFile := strings.TrimSuffix(*outputFile, ".json") + ".md"
		generateMarkdownReport(result, mdFile)
		fmt.Printf("\nMarkdown report written to %s\n", mdFile)
	}

	// Exit with non-zero if breaking changes found
	if result.BreakingCount > 0 {
		os.Exit(1)
	}
}

func generateMarkdownReport(result ComparisonResult, filename string) {
	var sb strings.Builder

	sb.WriteString("# Go Kopia API Changes Report\n\n")
	sb.WriteString(fmt.Sprintf("**Old Version**: %s\n", result.OldVersion))
	sb.WriteString(fmt.Sprintf("**New Version**: %s\n\n", result.NewVersion))

	sb.WriteString("## Summary\n\n")
	sb.WriteString(fmt.Sprintf("- **Breaking Changes**: %d\n", result.BreakingCount))
	sb.WriteString(fmt.Sprintf("- **Warnings**: %d\n", result.WarningCount))
	sb.WriteString(fmt.Sprintf("- **Info**: %d\n\n", result.InfoCount))

	if result.BreakingCount > 0 {
		sb.WriteString("## Breaking Changes\n\n")
		sb.WriteString("These changes require updates to the Kotlin implementation.\n\n")
		for _, ch := range result.Changes {
			if ch.Severity == SeverityBreaking {
				sb.WriteString(fmt.Sprintf("### %s - %s\n\n", ch.Package, ch.Name))
				sb.WriteString(fmt.Sprintf("- **Type**: %s\n", ch.Type))
				sb.WriteString(fmt.Sprintf("- **Change**: %s\n", ch.ChangeType))
				sb.WriteString(fmt.Sprintf("- **Description**: %s\n", ch.Description))
				if ch.OldValue != "" {
					sb.WriteString(fmt.Sprintf("- **Old**: `%s`\n", ch.OldValue))
				}
				if ch.NewValue != "" {
					sb.WriteString(fmt.Sprintf("- **New**: `%s`\n", ch.NewValue))
				}
				sb.WriteString("\n")
			}
		}
	}

	if result.WarningCount > 0 {
		sb.WriteString("## Warnings\n\n")
		sb.WriteString("These changes may affect compatibility.\n\n")
		for _, ch := range result.Changes {
			if ch.Severity == SeverityWarning {
				sb.WriteString(fmt.Sprintf("- **%s/%s**: %s\n", ch.Package, ch.Name, ch.Description))
			}
		}
		sb.WriteString("\n")
	}

	if len(result.KotlinImpacted) > 0 {
		sb.WriteString("## Kotlin Files to Review\n\n")
		for _, path := range result.KotlinImpacted {
			sb.WriteString(fmt.Sprintf("- `%s`\n", path))
		}
		sb.WriteString("\n")
	}

	if result.InfoCount > 0 {
		sb.WriteString("## Additions\n\n")
		sb.WriteString("New APIs that may be implemented in Kotlin.\n\n")
		for _, ch := range result.Changes {
			if ch.Severity == SeverityInfo {
				sb.WriteString(fmt.Sprintf("- **%s/%s**: %s\n", ch.Package, ch.Name, ch.Description))
			}
		}
	}

	os.WriteFile(filename, []byte(sb.String()), 0644)
}
