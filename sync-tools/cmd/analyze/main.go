// Package main provides a tool to analyze Go Kopia source code and extract
// interface definitions, struct types, and function signatures for compatibility
// tracking with the Kotlin implementation.
package main

import (
	"encoding/json"
	"flag"
	"fmt"
	"go/ast"
	"go/parser"
	"go/token"
	"go/types"
	"os"
	"path/filepath"
	"sort"
	"strings"
)

// AnalysisResult contains all extracted API information from Go source.
type AnalysisResult struct {
	Version    string                 `json:"version"`
	Timestamp  string                 `json:"timestamp"`
	Packages   map[string]PackageInfo `json:"packages"`
	Statistics Statistics             `json:"statistics"`
}

// Statistics contains summary counts.
type Statistics struct {
	TotalPackages   int `json:"totalPackages"`
	TotalInterfaces int `json:"totalInterfaces"`
	TotalStructs    int `json:"totalStructs"`
	TotalFunctions  int `json:"totalFunctions"`
	TotalConstants  int `json:"totalConstants"`
}

// PackageInfo contains extracted information about a Go package.
type PackageInfo struct {
	Name       string          `json:"name"`
	Path       string          `json:"path"`
	Interfaces []InterfaceInfo `json:"interfaces,omitempty"`
	Structs    []StructInfo    `json:"structs,omitempty"`
	Functions  []FunctionInfo  `json:"functions,omitempty"`
	Constants  []ConstantInfo  `json:"constants,omitempty"`
}

// InterfaceInfo describes a Go interface.
type InterfaceInfo struct {
	Name     string       `json:"name"`
	Doc      string       `json:"doc,omitempty"`
	Methods  []MethodInfo `json:"methods"`
	Embedded []string     `json:"embedded,omitempty"`
}

// MethodInfo describes a method signature.
type MethodInfo struct {
	Name    string      `json:"name"`
	Doc     string      `json:"doc,omitempty"`
	Params  []ParamInfo `json:"params,omitempty"`
	Returns []ParamInfo `json:"returns,omitempty"`
}

// ParamInfo describes a function parameter or return value.
type ParamInfo struct {
	Name string `json:"name,omitempty"`
	Type string `json:"type"`
}

// StructInfo describes a Go struct.
type StructInfo struct {
	Name     string      `json:"name"`
	Doc      string      `json:"doc,omitempty"`
	Fields   []FieldInfo `json:"fields,omitempty"`
	Embedded []string    `json:"embedded,omitempty"`
}

// FieldInfo describes a struct field.
type FieldInfo struct {
	Name string `json:"name"`
	Type string `json:"type"`
	Tag  string `json:"tag,omitempty"`
	Doc  string `json:"doc,omitempty"`
}

// FunctionInfo describes a top-level function.
type FunctionInfo struct {
	Name     string      `json:"name"`
	Doc      string      `json:"doc,omitempty"`
	Receiver string      `json:"receiver,omitempty"`
	Params   []ParamInfo `json:"params,omitempty"`
	Returns  []ParamInfo `json:"returns,omitempty"`
}

// ConstantInfo describes a constant value.
type ConstantInfo struct {
	Name  string `json:"name"`
	Type  string `json:"type,omitempty"`
	Value string `json:"value,omitempty"`
	Doc   string `json:"doc,omitempty"`
}

// Analyzer extracts API information from Go source.
type Analyzer struct {
	fset     *token.FileSet
	packages map[string]PackageInfo
	// Packages to analyze (relative to repo root)
	targetPackages []string
}

// NewAnalyzer creates a new source analyzer.
func NewAnalyzer() *Analyzer {
	return &Analyzer{
		fset:     token.NewFileSet(),
		packages: make(map[string]PackageInfo),
		targetPackages: []string{
			"repo",
			"repo/blob",
			"repo/blob/filesystem",
			"repo/blob/s3",
			"repo/blob/webdav",
			"repo/blob/sftp",
			"repo/content",
			"repo/content/index",
			"repo/encryption",
			"repo/hashing",
			"repo/compression",
			"repo/splitter",
			"repo/object",
			"repo/manifest",
			"repo/format",
			"snapshot",
			"snapshot/policy",
			"snapshot/restore",
			"snapshot/snapshotfs",
			"snapshot/snapshotmaintenance",
			"fs",
			"fs/localfs",
		},
	}
}

// AnalyzeDirectory analyzes Go source files in a directory.
func (a *Analyzer) AnalyzeDirectory(rootDir string) error {
	for _, pkgPath := range a.targetPackages {
		fullPath := filepath.Join(rootDir, pkgPath)
		if _, err := os.Stat(fullPath); os.IsNotExist(err) {
			continue // Skip non-existent packages
		}

		pkgs, err := parser.ParseDir(a.fset, fullPath, func(fi os.FileInfo) bool {
			// Skip test files
			return !strings.HasSuffix(fi.Name(), "_test.go")
		}, parser.ParseComments)
		if err != nil {
			fmt.Fprintf(os.Stderr, "Warning: failed to parse %s: %v\n", pkgPath, err)
			continue
		}

		for _, pkg := range pkgs {
			info := a.analyzePackage(pkg, pkgPath)
			if len(info.Interfaces) > 0 || len(info.Structs) > 0 ||
				len(info.Functions) > 0 || len(info.Constants) > 0 {
				a.packages[pkgPath] = info
			}
		}
	}

	return nil
}

func (a *Analyzer) analyzePackage(pkg *ast.Package, pkgPath string) PackageInfo {
	info := PackageInfo{
		Name: pkg.Name,
		Path: pkgPath,
	}

	for _, file := range pkg.Files {
		a.analyzeFile(file, &info)
	}

	// Sort for consistent output
	sort.Slice(info.Interfaces, func(i, j int) bool {
		return info.Interfaces[i].Name < info.Interfaces[j].Name
	})
	sort.Slice(info.Structs, func(i, j int) bool {
		return info.Structs[i].Name < info.Structs[j].Name
	})
	sort.Slice(info.Functions, func(i, j int) bool {
		return info.Functions[i].Name < info.Functions[j].Name
	})
	sort.Slice(info.Constants, func(i, j int) bool {
		return info.Constants[i].Name < info.Constants[j].Name
	})

	return info
}

func (a *Analyzer) analyzeFile(file *ast.File, info *PackageInfo) {
	for _, decl := range file.Decls {
		switch d := decl.(type) {
		case *ast.GenDecl:
			a.analyzeGenDecl(d, info)
		case *ast.FuncDecl:
			a.analyzeFuncDecl(d, info)
		}
	}
}

func (a *Analyzer) analyzeGenDecl(decl *ast.GenDecl, info *PackageInfo) {
	for _, spec := range decl.Specs {
		switch s := spec.(type) {
		case *ast.TypeSpec:
			a.analyzeTypeSpec(s, decl.Doc, info)
		case *ast.ValueSpec:
			if decl.Tok == token.CONST {
				a.analyzeConstSpec(s, info)
			}
		}
	}
}

func (a *Analyzer) analyzeTypeSpec(spec *ast.TypeSpec, doc *ast.CommentGroup, info *PackageInfo) {
	// Only export public types
	if !ast.IsExported(spec.Name.Name) {
		return
	}

	switch t := spec.Type.(type) {
	case *ast.InterfaceType:
		iface := InterfaceInfo{
			Name: spec.Name.Name,
			Doc:  getDocText(doc),
		}
		for _, field := range t.Methods.List {
			if len(field.Names) > 0 {
				// Named method
				method := MethodInfo{
					Name: field.Names[0].Name,
					Doc:  getDocText(field.Doc),
				}
				if ft, ok := field.Type.(*ast.FuncType); ok {
					method.Params = extractParams(ft.Params)
					method.Returns = extractParams(ft.Results)
				}
				iface.Methods = append(iface.Methods, method)
			} else {
				// Embedded interface
				iface.Embedded = append(iface.Embedded, typeToString(field.Type))
			}
		}
		info.Interfaces = append(info.Interfaces, iface)

	case *ast.StructType:
		str := StructInfo{
			Name: spec.Name.Name,
			Doc:  getDocText(doc),
		}
		if t.Fields != nil {
			for _, field := range t.Fields.List {
				if len(field.Names) > 0 {
					for _, name := range field.Names {
						if ast.IsExported(name.Name) {
							f := FieldInfo{
								Name: name.Name,
								Type: typeToString(field.Type),
								Doc:  getDocText(field.Doc),
							}
							if field.Tag != nil {
								f.Tag = field.Tag.Value
							}
							str.Fields = append(str.Fields, f)
						}
					}
				} else {
					// Embedded type
					str.Embedded = append(str.Embedded, typeToString(field.Type))
				}
			}
		}
		info.Structs = append(info.Structs, str)
	}
}

func (a *Analyzer) analyzeFuncDecl(decl *ast.FuncDecl, info *PackageInfo) {
	// Only export public functions
	if !ast.IsExported(decl.Name.Name) {
		return
	}

	fn := FunctionInfo{
		Name:    decl.Name.Name,
		Doc:     getDocText(decl.Doc),
		Params:  extractParams(decl.Type.Params),
		Returns: extractParams(decl.Type.Results),
	}

	if decl.Recv != nil && len(decl.Recv.List) > 0 {
		fn.Receiver = typeToString(decl.Recv.List[0].Type)
	}

	info.Functions = append(info.Functions, fn)
}

func (a *Analyzer) analyzeConstSpec(spec *ast.ValueSpec, info *PackageInfo) {
	for i, name := range spec.Names {
		if !ast.IsExported(name.Name) {
			continue
		}
		c := ConstantInfo{
			Name: name.Name,
			Doc:  getDocText(spec.Doc),
		}
		if spec.Type != nil {
			c.Type = typeToString(spec.Type)
		}
		if i < len(spec.Values) {
			c.Value = exprToString(spec.Values[i])
		}
		info.Constants = append(info.Constants, c)
	}
}

// GetResult returns the analysis result.
func (a *Analyzer) GetResult() AnalysisResult {
	result := AnalysisResult{
		Version:   "1.0",
		Timestamp: "", // Will be set by caller
		Packages:  a.packages,
	}

	// Calculate statistics
	for _, pkg := range a.packages {
		result.Statistics.TotalPackages++
		result.Statistics.TotalInterfaces += len(pkg.Interfaces)
		result.Statistics.TotalStructs += len(pkg.Structs)
		result.Statistics.TotalFunctions += len(pkg.Functions)
		result.Statistics.TotalConstants += len(pkg.Constants)
	}

	return result
}

// Helper functions

func getDocText(doc *ast.CommentGroup) string {
	if doc == nil {
		return ""
	}
	return strings.TrimSpace(doc.Text())
}

func extractParams(fields *ast.FieldList) []ParamInfo {
	if fields == nil {
		return nil
	}
	var params []ParamInfo
	for _, field := range fields.List {
		typeStr := typeToString(field.Type)
		if len(field.Names) > 0 {
			for _, name := range field.Names {
				params = append(params, ParamInfo{
					Name: name.Name,
					Type: typeStr,
				})
			}
		} else {
			params = append(params, ParamInfo{Type: typeStr})
		}
	}
	return params
}

func typeToString(expr ast.Expr) string {
	if expr == nil {
		return ""
	}
	return types.ExprString(expr)
}

func exprToString(expr ast.Expr) string {
	if expr == nil {
		return ""
	}
	switch e := expr.(type) {
	case *ast.BasicLit:
		return e.Value
	case *ast.Ident:
		return e.Name
	default:
		return types.ExprString(expr)
	}
}

func main() {
	var (
		repoDir    = flag.String("repo", "", "Path to Go Kopia repository")
		outputFile = flag.String("output", "api-snapshot.json", "Output JSON file")
		pretty     = flag.Bool("pretty", true, "Pretty-print JSON output")
	)
	flag.Parse()

	if *repoDir == "" {
		fmt.Fprintln(os.Stderr, "Error: -repo flag is required")
		flag.Usage()
		os.Exit(1)
	}

	analyzer := NewAnalyzer()
	if err := analyzer.AnalyzeDirectory(*repoDir); err != nil {
		fmt.Fprintf(os.Stderr, "Error analyzing directory: %v\n", err)
		os.Exit(1)
	}

	result := analyzer.GetResult()

	var data []byte
	var err error
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

	fmt.Printf("API snapshot written to %s\n", *outputFile)
	fmt.Printf("Statistics:\n")
	fmt.Printf("  Packages:   %d\n", result.Statistics.TotalPackages)
	fmt.Printf("  Interfaces: %d\n", result.Statistics.TotalInterfaces)
	fmt.Printf("  Structs:    %d\n", result.Statistics.TotalStructs)
	fmt.Printf("  Functions:  %d\n", result.Statistics.TotalFunctions)
	fmt.Printf("  Constants:  %d\n", result.Statistics.TotalConstants)
}
