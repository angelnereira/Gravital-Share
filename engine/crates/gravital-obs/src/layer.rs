use tracing_subscriber::Layer;
use tracing::Subscriber;

/// Custom tracing Layer that routes `tracing` events to the global GsEvent sink.
pub struct GravitalLayer;

impl<S: Subscriber> Layer<S> for GravitalLayer {
    fn on_event(&self, event: &tracing::Event<'_>, _ctx: tracing_subscriber::layer::Context<'_, S>) {
        use tracing::Level;
        use crate::event::GsEvent;
        use serde_json::json;

        let lvl = match *event.metadata().level() {
            Level::TRACE => crate::event::Level::Trace,
            Level::DEBUG => crate::event::Level::Debug,
            Level::INFO  => crate::event::Level::Info,
            Level::WARN  => crate::event::Level::Warn,
            Level::ERROR => crate::event::Level::Error,
        };

        let mut visitor = FieldVisitor::default();
        event.record(&mut visitor);

        let kind = visitor.kind.unwrap_or_else(|| event.metadata().name().to_owned());
        let module = event.metadata().module_path().unwrap_or("unknown").to_owned();

        let ev = GsEvent::new(lvl, kind, module, json!(visitor.fields));
        crate::emit(ev);
    }
}

#[derive(Default)]
struct FieldVisitor {
    kind: Option<String>,
    fields: std::collections::HashMap<String, String>,
}

impl tracing::field::Visit for FieldVisitor {
    fn record_str(&mut self, field: &tracing::field::Field, value: &str) {
        if field.name() == "kind" {
            self.kind = Some(value.to_owned());
        } else {
            self.fields.insert(field.name().to_owned(), value.to_owned());
        }
    }

    fn record_debug(&mut self, field: &tracing::field::Field, value: &dyn std::fmt::Debug) {
        self.fields.insert(field.name().to_owned(), format!("{value:?}"));
    }
}
