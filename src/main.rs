use clap::{Parser, Subcommand};
use kdl::KdlDocument;
use miette::Result;
use std::fs;
use dotenvy::dotenv;
use inquire::Text;

// Modules
mod dsl;
mod compiler;
mod network;
mod action;
use dsl::DslCommand;
use network::Provider; // Import the Enum

#[derive(Parser)]
#[command(name = "kcx", version = "0.3")]
struct Cli {
    /// Which AI brain to use? (claude | gemini)
    #[arg(short, long, value_enum, default_value_t = Provider::Gemini)]
    provider: Provider,

    #[command(subcommand)]
    command: Commands,
}

#[derive(Subcommand)]
enum Commands {
    Init { project: String },
    Exec { 
        #[arg(short, long)]
        dsl: String 
    },
    Repl,
    Status,
}

#[tokio::main]
async fn main() -> Result<()> {
    dotenv().ok();
    let cli = Cli::parse();
    
    // We pass the provider choice down to the runner
    let provider = cli.provider;

    match &cli.command {
        Commands::Init { project } => {
            println!("🚀 Initializing: {}", project);
        }
        Commands::Exec { dsl } => {
            // Pass provider to run_turn
            run_turn(&dsl, provider).await?;
        }
        Commands::Status => {
            let filename = "kcx_state.kdl";
            let content = fs::read_to_string(filename).unwrap_or("No state file.".to_string());
            println!("{}", content);
        }
        Commands::Repl => {
            run_repl(provider).await?;
        }
    }

    Ok(())
}

async fn run_repl(provider: Provider) -> Result<()> {
    println!("🔌 KC-X Connected via {:?}. Type 'exit' to quit.", provider);
    
    loop {
        let input = Text::new("kcx>").prompt();
        match input {
            Ok(text) => {
                let text = text.trim();
                if text == "exit" || text == "quit" { break; }
                if text.is_empty() { continue; }

                if let Err(e) = run_turn(text, provider).await {
                    println!("💥 Error: {}", e);
                }
            }
            Err(_) => break,
        }
    }
    println!("🔌 Disconnected.");
    Ok(())
}

async fn run_turn(input: &str, provider: Provider) -> Result<()> {
    // 1. Parse
    let parsed = DslCommand::parse(input);
    let cmd = parsed.unwrap_or_else(|| DslCommand {
        verb: "chat".to_string(),
        target: "current_context".to_string(),
        includes: vec![],
        excludes: vec![],
        redirect: None, // <--- ADDED
        agent: None,    // <--- ADDED
    });

    // 2. Load State
    let filename = "kcx_state.kdl";
    let content = fs::read_to_string(filename).unwrap_or_default();
    let state_doc: KdlDocument = content.parse().unwrap_or_default();

    // 3. Compile
    let system_prompt = compiler::compile_system_prompt(&state_doc, &cmd);

    // 4. Network (Using the Dispatcher!)
    println!("Thinking ({:?})...", provider);
    
    // CALL THE DISPATCHER
    let response = network::send_request(provider, system_prompt, input.to_string()).await?;

    // 5. Act
    println!("\n🤖: {}\n", response);
    action::apply_changes(&response)?;

    Ok(())
}
