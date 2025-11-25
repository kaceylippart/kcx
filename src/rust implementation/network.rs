use miette::{IntoDiagnostic, Result, miette};
use reqwest::Client;
use serde_json::json;
use std::env;

// 1. Define the Providers
#[derive(Debug, Clone, Copy, PartialEq, Eq, clap::ValueEnum)]
pub enum Provider {
    Claude,
    Gemini,
}

// 2. The Dispatcher (Main Entry Point)
pub async fn send_request(
    provider: Provider, 
    system_prompt: String, 
    user_input: String
) -> Result<String> {
    match provider {
        Provider::Claude => send_to_claude(system_prompt, user_input).await,
        Provider::Gemini => send_to_gemini(system_prompt, user_input).await,
    }
}

// --- CLAUDE IMPLEMENTATION ---
async fn send_to_claude(system: String, user: String) -> Result<String> {
    let api_key = env::var("ANTHROPIC_API_KEY")
        .map_err(|_| miette!("❌ ANTHROPIC_API_KEY not found"))?;

    let client = Client::new();
    
    let response = client
        .post("https://api.anthropic.com/v1/messages")
        .header("x-api-key", api_key)
        .header("anthropic-version", "2023-06-01")
        .header("content-type", "application/json")
        .json(&json!({
            "model": "claude-3-5-sonnet-20241022",
            "max_tokens": 4096,
            "system": system, 
            "messages": [{ "role": "user", "content": user }]
        }))
        .send()
        .await
        .into_diagnostic()?;

    if !response.status().is_success() {
        let err = response.text().await.into_diagnostic()?;
        return Err(miette!("Claude API Error: {}", err));
    }

    let json: serde_json::Value = response.json().await.into_diagnostic()?;
    Ok(json["content"][0]["text"].as_str().unwrap_or("").to_string())
}

// --- GEMINI IMPLEMENTATION ---
async fn send_to_gemini(system: String, user: String) -> Result<String> {
    let api_key = env::var("GEMINI_API_KEY")
        .map_err(|_| miette!("❌ GEMINI_API_KEY not found"))?;
    
    // Default to 2.5-pro if not set
    let model = env::var("GEMINI_MODEL").unwrap_or_else(|_| "gemini-2.5-pro".to_string());

    let client = Client::new();
    let url = format!(
        "https://generativelanguage.googleapis.com/v1beta/models/{}:generateContent?key={}", 
        model, api_key
    );

    let response = client
        .post(url)
        .header("Content-Type", "application/json")
        .json(&json!({
            "systemInstruction": { "parts": [{ "text": system }] },
            "contents": [{ "role": "user", "parts": [{ "text": user }] }],
            "generationConfig": { "temperature": 0.2 }
        }))
        .send()
        .await
        .into_diagnostic()?;

    if !response.status().is_success() {
        let err = response.text().await.into_diagnostic()?;
        return Err(miette!("Gemini API Error: {}", err));
    }

    let json: serde_json::Value = response.json().await.into_diagnostic()?;
    // Gemini response path: candidates[0].content.parts[0].text
    Ok(json["candidates"][0]["content"]["parts"][0]["text"]
        .as_str()
        .unwrap_or("")
        .to_string())
}
