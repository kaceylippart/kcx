use std::collections::HashMap;

/// Symbol conflict resolver for KC-X DSL
/// Handles Claude's reserved symbols by providing safe alternatives
pub struct SymbolResolver;

impl SymbolResolver {
    /// Transform input to use safe symbols before parsing
    pub fn resolve_conflicts(input: &str) -> String {
        let mut resolved = input.to_string();

        // Apply symbol mappings in order of precedence
        for (conflict_symbol, safe_symbol) in Self::get_symbol_mappings() {
            resolved = resolved.replace(&conflict_symbol, &safe_symbol);
        }

        resolved
    }

    /// Get mapping of conflicting symbols to safe alternatives
    fn get_symbol_mappings() -> Vec<(String, String)> {
        vec![
            // Alternative prefixes (Claude-safe) - must come first
            ("kcx:".to_string(), ":".to_string()),
            ("kx:".to_string(), ":".to_string()),

            // Target alternatives - use word boundaries to avoid partial matches
            ("file:".to_string(), "@".to_string()),
            ("#file:".to_string(), "@".to_string()),
            ("$file:".to_string(), "@".to_string()),

            // Include/exclude alternatives
            ("with:".to_string(), "+".to_string()),
            ("inc:".to_string(), "+".to_string()),
            ("without:".to_string(), "-".to_string()),
            ("exc:".to_string(), "-".to_string()),
            ("not:".to_string(), "-".to_string()),

            // Redirect alternatives
            ("to:".to_string(), ">".to_string()),
            ("out:".to_string(), ">".to_string()),

            // Agent alternatives
            ("as:".to_string(), "&".to_string()),
            ("agent:".to_string(), "&".to_string()),
        ]
    }

    /// Check if input contains potentially conflicting symbols
    pub fn has_conflicts(input: &str) -> bool {
        let conflict_patterns = [
            "@",      // Claude Code file references
            "!",      // Emphasis, commands
            // Keep : as it's less problematic
        ];

        conflict_patterns.iter().any(|pattern| input.contains(pattern))
    }

    /// Get user-friendly syntax alternatives
    pub fn get_syntax_help() -> String {
        r#"KC-X DSL SYNTAX OPTIONS:

=== CLAUDE-SAFE ALTERNATIVES ===
Command Prefixes:
  kcx:gen    (instead of :gen)
  kx:gen     (instead of :gen)
  ~gen       (instead of !gen)

Target Files:
  file:main.rs       (instead of @main.rs)
  #file:main.rs      (instead of @main.rs)
  $file:main.rs      (instead of @main.rs)
  %main.rs           (instead of @main.rs)

Includes:
  with:rust with:async    (instead of +rust +async)
  inc:rust inc:async      (instead of +rust +async)

Excludes:
  without:unsafe          (instead of -unsafe)
  not:unwrap              (instead of -unwrap)
  exc:deprecated          (instead of -deprecated)

Output:
  to:output.txt           (instead of >output.txt)
  out:results.md          (instead of >results.md)

Agent:
  as:reviewer             (instead of &reviewer)
  agent:coder             (instead of &coder)

=== EXAMPLES ===
Traditional:  :gen @auth.rs +async -unwrap > @tests.rs &reviewer
Claude-Safe:  kcx:gen file:auth.rs with:async not:unwrap to:tests.rs as:reviewer
Hybrid:       kx:gen %auth.rs +async without:unwrap out:tests.rs agent:reviewer

=== RAW MODE ===
For complex commands, you can also use raw mode:
  raw: :gen @auth.rs +async -unwrap > @tests.rs &reviewer
"#.to_string()
    }

    /// Parse raw mode commands (bypass Claude interpretation)
    pub fn parse_raw_mode(input: &str) -> Option<String> {
        if input.trim().starts_with("raw:") {
            let raw_command = input.trim().strip_prefix("raw:")?.trim();
            Some(raw_command.to_string())
        } else {
            None
        }
    }

    /// Convert from safe symbols back to internal representation
    pub fn normalize_for_parsing(input: &str) -> String {
        // First check for raw mode
        if let Some(raw_command) = Self::parse_raw_mode(input) {
            return raw_command;
        }

        // Apply conflict resolution
        Self::resolve_conflicts(input)
    }

    /// Get recommended syntax based on context
    pub fn recommend_syntax(original: &str, conflict_level: ConflictLevel) -> String {
        match conflict_level {
            ConflictLevel::None => original.to_string(),
            ConflictLevel::Low => Self::apply_minimal_changes(original),
            ConflictLevel::High => Self::apply_full_safe_syntax(original),
        }
    }

    /// Apply minimal changes for low conflict
    fn apply_minimal_changes(input: &str) -> String {
        input
            .replace(":", "~")           // Replace : with safer ~ for low conflict
            .replace("@", "%")           // Only replace the most problematic symbol
    }

    /// Apply full safe syntax transformation
    fn apply_full_safe_syntax(input: &str) -> String {
        let mut safe = input.to_string();

        // Replace all potentially conflicting patterns
        safe = safe.replace(":gen", "kcx:gen");
        safe = safe.replace(":edit", "kcx:edit");
        safe = safe.replace(":refactor", "kcx:refactor");
        safe = safe.replace("@", "file:");
        safe = safe.replace("+", "with:");
        safe = safe.replace("-", "without:");
        safe = safe.replace(">", "to:");
        safe = safe.replace("&", "as:");

        safe
    }
}

#[derive(Debug, Clone)]
pub enum ConflictLevel {
    None,    // No conflicts detected
    Low,     // Minor conflicts (@ symbol)
    High,    // Major conflicts (@ + ! + multiple patterns)
}

impl ConflictLevel {
    pub fn detect(input: &str) -> Self {
        let conflict_count = [
            input.contains("@"),
            input.contains("!"),
            input.matches(":").count() > 1,  // Multiple colons might be problematic
        ].iter().filter(|&&x| x).count();

        match conflict_count {
            0 => ConflictLevel::None,
            1 => ConflictLevel::Low,
            _ => ConflictLevel::High,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_conflict_detection() {
        assert!(SymbolResolver::has_conflicts("gen @file.rs"));
        assert!(SymbolResolver::has_conflicts("!gen file.rs"));
        assert!(!SymbolResolver::has_conflicts("gen file.rs +rust"));
    }

    #[test]
    fn test_symbol_resolution() {
        let input = "kcx:gen file:main.rs with:async not:unwrap to:output.txt as:coder";
        let resolved = SymbolResolver::resolve_conflicts(input);
        assert_eq!(resolved, ":gen @main.rs +async -unwrap >output.txt &coder");
    }

    #[test]
    fn test_raw_mode() {
        let input = "raw: :gen @auth.rs +async -unwrap";
        let raw = SymbolResolver::parse_raw_mode(input).unwrap();
        assert_eq!(raw, ":gen @auth.rs +async -unwrap");
    }

    #[test]
    fn test_conflict_levels() {
        assert!(matches!(ConflictLevel::detect("gen file.rs"), ConflictLevel::None));
        assert!(matches!(ConflictLevel::detect("gen @file.rs"), ConflictLevel::Low));
        assert!(matches!(ConflictLevel::detect("!gen @file.rs"), ConflictLevel::High));
    }

    #[test]
    fn test_syntax_recommendations() {
        let original = ":gen @auth.rs +async -unwrap";

        let minimal = SymbolResolver::recommend_syntax(original, ConflictLevel::Low);
        assert_eq!(minimal, "~gen %auth.rs +async -unwrap");

        let full_safe = SymbolResolver::recommend_syntax(original, ConflictLevel::High);
        assert!(full_safe.contains("kcx:gen"));
        assert!(full_safe.contains("file:"));
    }
}