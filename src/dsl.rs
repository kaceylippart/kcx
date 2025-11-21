use regex::Regex;

#[derive(Debug)]
pub struct DslCommand {
    pub verb: String,
    pub target: String,
    pub includes: Vec<String>,
    pub excludes: Vec<String>,
    // THESE WERE MISSING:
    pub redirect: Option<String>, 
    pub agent: Option<String>,
}

impl DslCommand {
    pub fn parse(input: &str) -> Option<Self> {
        let main_pattern = Regex::new(r"^!(\w+)\s+@([\w_./-]+)").ok()?;
        let caps = main_pattern.captures(input)?;
        
        let verb = caps.get(1)?.as_str().to_string();
        let target = caps.get(2)?.as_str().to_string();

        let include_re = Regex::new(r"\+(\w+)").ok()?;
        let includes: Vec<String> = include_re.find_iter(input)
            .map(|m| m.as_str().trim_start_matches('+').to_string())
            .collect();

        let exclude_re = Regex::new(r"-(\w+)").ok()?;
        let excludes: Vec<String> = exclude_re.find_iter(input)
            .map(|m| m.as_str().trim_start_matches('-').to_string())
            .collect();

        // NEW LOGIC
        let redirect_re = Regex::new(r">\s*@?([\w_./-]+)").ok()?;
        let redirect = redirect_re.captures(input)
            .map(|c| c.get(1).unwrap().as_str().to_string());

        let agent_re = Regex::new(r"&(\w+)").ok()?;
        let agent = agent_re.captures(input)
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
