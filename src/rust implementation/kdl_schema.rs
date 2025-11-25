use kdl::{KdlDocument, KdlNode};
use std::collections::HashMap;

#[derive(Debug, Clone)]
pub struct KdlValidationError {
    pub message: String,
}

impl std::fmt::Display for KdlValidationError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "KDL Validation Error: {}", self.message)
    }
}

impl std::error::Error for KdlValidationError {}

pub struct MemoryFileValidator;

impl MemoryFileValidator {
    pub fn validate(content: &str) -> Result<(), KdlValidationError> {
        let doc: KdlDocument = content.parse()
            .map_err(|e| KdlValidationError {
                message: format!("Invalid KDL syntax: {}", e)
            })?;

        Self::validate_document(&doc)
    }

    fn validate_document(doc: &KdlDocument) -> Result<(), KdlValidationError> {
        let nodes: HashMap<String, &KdlNode> = doc.nodes()
            .iter()
            .map(|node| (node.name().value().to_string(), node))
            .collect();

        // Validate required top-level sections
        Self::validate_meta_section(nodes.get("meta").copied())?;
        Self::validate_stack_section(nodes.get("stack").copied())?;
        Self::validate_active_context_section(nodes.get("active_context").copied())?;
        Self::validate_memory_section(nodes.get("memory").copied())?;

        // Check for unexpected top-level nodes
        let expected_sections = ["meta", "stack", "active_context", "memory"];
        for name in nodes.keys() {
            if !expected_sections.contains(&name.as_str()) {
                return Err(KdlValidationError {
                    message: format!("Unexpected top-level section: '{}'", name)
                });
            }
        }

        Ok(())
    }

    fn validate_meta_section(node: Option<&KdlNode>) -> Result<(), KdlValidationError> {
        let node = node.ok_or_else(|| KdlValidationError {
            message: "Missing required 'meta' section".to_string()
        })?;

        let children = node.children().ok_or_else(|| KdlValidationError {
            message: "Meta section must have child nodes".to_string()
        })?;

        let child_map: HashMap<String, &KdlNode> = children.nodes()
            .iter()
            .map(|n| (n.name().value().to_string(), n))
            .collect();

        // Validate required meta fields
        Self::validate_string_field(&child_map, "version", "meta")?;
        Self::validate_string_field(&child_map, "author", "meta")?;

        Ok(())
    }

    fn validate_stack_section(node: Option<&KdlNode>) -> Result<(), KdlValidationError> {
        let node = node.ok_or_else(|| KdlValidationError {
            message: "Missing required 'stack' section".to_string()
        })?;

        let children = node.children().ok_or_else(|| KdlValidationError {
            message: "Stack section must have child nodes".to_string()
        })?;

        let child_map: HashMap<String, &KdlNode> = children.nodes()
            .iter()
            .map(|n| (n.name().value().to_string(), n))
            .collect();

        // Validate required stack fields
        Self::validate_string_field(&child_map, "language", "stack")?;
        Self::validate_string_field(&child_map, "framework", "stack")?;

        Ok(())
    }

    fn validate_active_context_section(node: Option<&KdlNode>) -> Result<(), KdlValidationError> {
        let node = node.ok_or_else(|| KdlValidationError {
            message: "Missing required 'active_context' section".to_string()
        })?;

        let children = node.children().ok_or_else(|| KdlValidationError {
            message: "Active_context section must have child nodes".to_string()
        })?;

        let child_map: HashMap<String, &KdlNode> = children.nodes()
            .iter()
            .map(|n| (n.name().value().to_string(), n))
            .collect();

        // Validate required active_context fields
        Self::validate_string_field(&child_map, "task", "active_context")?;
        Self::validate_string_field(&child_map, "status", "active_context")?;

        Ok(())
    }

    fn validate_memory_section(node: Option<&KdlNode>) -> Result<(), KdlValidationError> {
        let node = node.ok_or_else(|| KdlValidationError {
            message: "Missing required 'memory' section".to_string()
        })?;

        let children = node.children().ok_or_else(|| KdlValidationError {
            message: "Memory section must have child nodes".to_string()
        })?;

        // Validate decision entries
        for child in children.nodes() {
            if child.name().value() != "decision" {
                return Err(KdlValidationError {
                    message: format!("Memory section can only contain 'decision' nodes, found: '{}'", child.name().value())
                });
            }

            // Validate decision has a description (first argument)
            if child.entries().is_empty() {
                return Err(KdlValidationError {
                    message: "Decision node must have a description as the first argument".to_string()
                });
            }

            // Validate decision has a date property
            let has_date = child.entries().iter().any(|entry| {
                entry.name().map(|n| n.value() == "date").unwrap_or(false)
            });

            if !has_date {
                return Err(KdlValidationError {
                    message: "Decision node must have a 'date' property".to_string()
                });
            }
        }

        Ok(())
    }

    fn validate_string_field(
        child_map: &HashMap<String, &KdlNode>,
        field_name: &str,
        section_name: &str
    ) -> Result<(), KdlValidationError> {
        let node = child_map.get(field_name).ok_or_else(|| KdlValidationError {
            message: format!("Missing required '{}' field in '{}' section", field_name, section_name)
        })?;

        if node.entries().is_empty() {
            return Err(KdlValidationError {
                message: format!("Field '{}' in '{}' section must have a value", field_name, section_name)
            });
        }

        let entry = &node.entries()[0];
        if !matches!(entry.value(), kdl::KdlValue::String(_)) {
            return Err(KdlValidationError {
                message: format!("Field '{}' in '{}' section must be a string", field_name, section_name)
            });
        }

        Ok(())
    }

    pub fn create_template() -> String {
        r#"meta {
    version "1.0"
    author "KC-X"
}
stack {
    language "Rust"
    framework "Axum"
}
active_context {
    task "New Task"
    status "Ready to begin"
}
memory {
    decision "Initial setup completed" date="2025-11-21"
}
"#.to_string()
    }

    pub fn migrate_legacy_format(content: &str) -> Result<String, KdlValidationError> {
        // Try to parse as legacy format and convert to new format
        let doc: KdlDocument = content.parse()
            .map_err(|e| KdlValidationError {
                message: format!("Invalid KDL syntax: {}", e)
            })?;

        // Check if it's already in the correct format
        if Self::validate_document(&doc).is_ok() {
            return Ok(content.to_string());
        }

        // If it's legacy format, try to migrate
        let mut new_doc = String::new();

        // Start with template
        new_doc.push_str("meta {\n    version \"1.0\"\n    author \"KC-X\"\n}\n");
        new_doc.push_str("stack {\n    language \"Rust\"\n    framework \"Unknown\"\n}\n");
        new_doc.push_str("active_context {\n    task \"Migrated from legacy format\"\n    status \"Please update with current context\"\n}\n");
        new_doc.push_str("memory {\n");

        // Try to extract any existing decisions or tasks and convert them
        for node in doc.nodes() {
            match node.name().value() {
                "task" => {
                    if let Some(desc_entry) = node.get("description") {
                        if let kdl::KdlValue::String(desc) = desc_entry.value() {
                            new_doc.push_str(&format!("    decision \"{}\" date=\"2025-11-21\"\n", desc));
                        }
                    }
                }
                "meta" => {
                    // Skip, we'll use our own meta section
                }
                _ => {
                    // Convert other nodes to decisions
                    if let Some(first_entry) = node.entries().first() {
                        if let kdl::KdlValue::String(value) = first_entry.value() {
                            new_doc.push_str(&format!("    decision \"{}\" date=\"2025-11-21\"\n", value));
                        }
                    }
                }
            }
        }

        new_doc.push_str("}\n");

        Ok(new_doc)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_valid_schema() {
        let content = r#"
meta {
    version "1.0"
    author "KC-X"
}
stack {
    language "Rust"
    framework "Axum"
}
active_context {
    task "Implement User Model"
    status "Database connection complete"
}
memory {
    decision "Selected KDL format" date="2025-11-01"
    decision "Selected Rust" date="2025-11-02"
}
"#;

        assert!(MemoryFileValidator::validate(content).is_ok());
    }

    #[test]
    fn test_missing_meta_section() {
        let content = r#"
stack {
    language "Rust"
    framework "Axum"
}
active_context {
    task "Test"
    status "Testing"
}
memory {
    decision "Test decision" date="2025-11-21"
}
"#;

        let result = MemoryFileValidator::validate(content);
        assert!(result.is_err());
        assert!(result.unwrap_err().message.contains("Missing required 'meta' section"));
    }

    #[test]
    fn test_invalid_decision_without_date() {
        let content = r#"
meta {
    version "1.0"
    author "KC-X"
}
stack {
    language "Rust"
    framework "Axum"
}
active_context {
    task "Test"
    status "Testing"
}
memory {
    decision "Test decision without date"
}
"#;

        let result = MemoryFileValidator::validate(content);
        assert!(result.is_err());
        assert!(result.unwrap_err().message.contains("must have a 'date' property"));
    }

    #[test]
    fn test_template_creation() {
        let template = MemoryFileValidator::create_template();
        assert!(MemoryFileValidator::validate(&template).is_ok());
    }
}