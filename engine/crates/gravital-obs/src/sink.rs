/// Write destination for structured log lines.
pub trait Sink: Send + 'static {
    fn write(&mut self, json_line: &str);
}

/// Writes events to stdout — used in development and CI.
pub struct StdoutSink;

impl Sink for StdoutSink {
    fn write(&mut self, json_line: &str) {
        println!("{json_line}");
    }
}

/// Discards all events — used in tests that don't care about logs.
pub struct NullSink;

impl Sink for NullSink {
    fn write(&mut self, _json_line: &str) {}
}

/// Buffers events in memory — used in tests that inspect emitted events.
pub struct BufferSink {
    pub lines: Vec<String>,
}

impl BufferSink {
    pub fn new() -> Self {
        Self { lines: Vec::new() }
    }
}

impl Default for BufferSink {
    fn default() -> Self {
        Self::new()
    }
}

impl Sink for BufferSink {
    fn write(&mut self, json_line: &str) {
        self.lines.push(json_line.to_owned());
    }
}
