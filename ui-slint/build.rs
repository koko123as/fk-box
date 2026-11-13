fn main() {
    // @tr() strings carry no context; i18n/zh holds the Simplified Chinese
    // that src/i18n.rs reads too.
    let config = slint_build::CompilerConfiguration::new()
        .with_style("material".into())
        .with_bundled_translations("i18n")
        .with_default_translation_context(slint_build::DefaultTranslationContext::None);
    // FKUI_LIGHT=1 compiles only the globals (ui/check.slint) for low-memory
    // type checks of the Rust side; release builds always use ui/app.slint.
    let main = if std::env::var("FKUI_LIGHT").is_ok() { "ui/check.slint" } else { "ui/app.slint" };
    slint_build::compile_with_config(main, config).expect("Slint build failed");
    println!("cargo:rerun-if-changed=i18n");
    println!("cargo:rerun-if-env-changed=FKUI_LIGHT");
}
