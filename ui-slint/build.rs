fn main() {
    // @tr() strings carry no context, so the .po generated from the Compose
    // UI's BoxStrings.kt (tools/gen_slint_i18n.py) applies as is.
    let config = slint_build::CompilerConfiguration::new()
        .with_style("material".into())
        .with_bundled_translations("i18n")
        .with_default_translation_context(slint_build::DefaultTranslationContext::None);
    slint_build::compile_with_config("ui/app.slint", config).expect("Slint build failed");
    println!("cargo:rerun-if-changed=i18n");
}
