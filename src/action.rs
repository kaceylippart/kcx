use regex::Regex;
use std::fs;
use std::path::Path;
use miette::Result;
use kdl::KdlDocument;
use inquire::Confirm; // For the Y/N prompt
use similar::{ChangeTag, TextDiff}; // For diff calculation
use console::{style, Emoji}; // For colors

pub fn apply_changes(response: &str) -> Result<()> {
    // 1. CAPTURE FILE OPERATIONS
    let file_pattern = Regex::new(r#"(?s)<file path="([^"]+)">\n?(.*?)\n?</file>"#)
        .expect("Invalid File Regex");

    let mut pending_changes = Vec::new();

    for cap in file_pattern.captures_iter(response) {
        let path_str = cap[1].to_string();
        let new_content = cap[2].to_string();
        pending_changes.push((path_str, new_content));
    }

    if pending_changes.is_empty() {
        // Check for state updates even if no files changed
        handle_state_update(response);
        return Ok(());
    }

    // 2. SHOW DIFFS & ASK FOR CONFIRMATION
    println!("\n🔍 {} Proposed Changes:", pending_changes.len());
    println!("{}", style("==========================================").dim());

    for (path_str, new_content) in &pending_changes {
        let path = Path::new(path_str);
        
        if path.exists() {
            // It's an edit - Show Diff
            let old_content = fs::read_to_string(path).unwrap_or_default();
            print_diff(path_str, &old_content, new_content);
        } else {
            // It's a new file
            println!("📄 {}: {}", style("NEW FILE").green().bold(), path_str);
            // Optionally print the first few lines
            let preview: String = new_content.lines().take(3).collect::<Vec<_>>().join("\n");
            println!("{}", style(preview).dim());
            println!("{}", style("...").dim());
        }
        println!("{}", style("------------------------------------------").dim());
    }

    // 3. THE SAFETY GATE
    let confirm = Confirm::new("Apply these changes?")
        .with_default(true)
        .prompt();

    match confirm {
        Ok(true) => {
            for (path_str, new_content) in pending_changes {
                write_file(&path_str, &new_content)?;
            }
            // Only update state if the files were actually written!
            handle_state_update(response);
        }
        Ok(false) => {
            println!("❌ Changes discarded.");
        }
        Err(_) => println!("❌ Operation cancelled."),
    }

    Ok(())
}

fn write_file(path_str: &str, content: &str) -> Result<()> {
    let path = Path::new(path_str);
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent).expect("Failed to create directory");
    }
    println!("{} Writing: {}", Emoji("💾", "Save"), path_str);
    fs::write(path, content).expect("Failed to write file");
    Ok(())
}

fn handle_state_update(response: &str) {
    let state_pattern = Regex::new(r"(?si)\[KC-X UPDATE\]\s*(?:```[a-z]*\n)?(.*?)(?:```|$)")
        .expect("Invalid State Regex");

    if let Some(cap) = state_pattern.captures(response) {
        let new_kdl_content = cap[1].trim();
        if new_kdl_content.parse::<KdlDocument>().is_ok() {
            println!("{} Updating Memory Bank...", Emoji("🧠", "Brain"));
            let _ = fs::write("kcx_state.kdl", new_kdl_content);
        }
    }
}

// Helper to print colored diffs (Git style)
fn print_diff(filename: &str, old: &str, new: &str) {
    println!("📝 Modifying: {}", style(filename).bold());
    
    let diff = TextDiff::from_lines(old, new);

    for (idx, group) in diff.grouped_ops(3).iter().enumerate() {
        if idx > 0 {
            println!("...");
        }
        for op in group {
            for change in diff.iter_changes(op) {
                let (sign, s) = match change.tag() {
                    ChangeTag::Delete => ("-", style(change.to_string()).red()),
                    ChangeTag::Insert => ("+", style(change.to_string()).green()),
                    ChangeTag::Equal => (" ", style(change.to_string()).dim()),
                };
                print!("{}{} ", sign, change.old_index().map(|i| i + 1).unwrap_or(0)); // simple line num
                print!("{}", s);
            }
        }
    }
}
