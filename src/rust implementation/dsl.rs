use regex::Regex;
use lazy_static::lazy_static;
use serde::{Deserialize, Serialize};

lazy_static! {
    static ref VERB_RE: Regex = Regex::new(r"^[!:/\s]*(\w+)").unwrap();
    static ref TARGET_RE: Regex = Regex::new(r"@([\w_./-]+)").unwrap();
    static ref INCLUDE_RE: Regex = Regex::new(r"\+(\w+)").unwrap();
    static ref EXCLUDE_RE: Regex = Regex::new(r"-(\w+)").unwrap();
    static ref REDIRECT_RE: Regex = Regex::new(r">\s*@?([\w_./-]+)").unwrap();
    static ref AGENT_RE: Regex = Regex::new(r"&(\w+)").unwrap();
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DslCommand {
    pub verb: String,
    pub target: String,
    pub includes: Vec<String>,
    pub excludes: Vec<String>,
    pub redirect: Option<String>,
    pub agent: Option<String>,
}

impl DslCommand {
    pub fn parse(input: &str) -> Option<Self> {
        // 1. PARSE VERB
        let verb_cap = VERB_RE.captures(input)?;
        let verb = verb_cap.get(1)?.as_str().to_string();

        // 2. PARSE TARGET (Optional - Default to "global_context")
        let (target, remaining_input) = match TARGET_RE.captures(input) {
            Some(cap) => {
                let target = cap.get(1).unwrap().as_str().to_string();
                let full_match = cap.get(0).unwrap();
                // Create remaining input without the target portion
                let mut remaining = String::new();
                remaining.push_str(&input[..full_match.start()]);
                remaining.push_str(&input[full_match.end()..]);
                (target, remaining)
            },
            None => ("global_context".to_string(), input.to_string()),
        };

        // 3. Constraints (+ / -) - Parse from remaining input only
        let includes: Vec<String> = INCLUDE_RE
            .find_iter(&remaining_input)
            .map(|m| m.as_str().trim_start_matches('+').to_string())
            .collect();

        let excludes: Vec<String> = EXCLUDE_RE
            .find_iter(&remaining_input)
            .map(|m| m.as_str().trim_start_matches('-').to_string())
            .collect();

        // 4. Redirect (>) - Parse from remaining input
        let redirect = REDIRECT_RE
            .captures(&remaining_input)
            .map(|c| c.get(1).unwrap().as_str().to_string());

        // 5. Agent (&) - Parse from remaining input
        let agent = AGENT_RE
            .captures(&remaining_input)
            .map(|c| c.get(1).unwrap().as_str().to_string());

        Some(DslCommand {
            verb,
            target,
            includes,
            excludes,
            redirect,
            agent,
        })
    }
}
