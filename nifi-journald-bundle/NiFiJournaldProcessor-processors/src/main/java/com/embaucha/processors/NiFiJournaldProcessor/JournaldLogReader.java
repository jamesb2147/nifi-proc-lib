package com.embaucha.processors.NiFiJournaldProcessor;

import org.apache.nifi.annotation.behavior.InputRequirement;
import org.apache.nifi.annotation.behavior.InputRequirement.Requirement;
import org.apache.nifi.annotation.behavior.WritesAttribute;
import org.apache.nifi.annotation.behavior.WritesAttributes;
import org.apache.nifi.annotation.documentation.CapabilityDescription;
import org.apache.nifi.annotation.documentation.Tags;
import org.apache.nifi.annotation.lifecycle.OnScheduled;
import org.apache.nifi.annotation.lifecycle.OnStopped;
import org.apache.nifi.components.AllowableValue;
import org.apache.nifi.components.PropertyDescriptor;
import org.apache.nifi.expression.ExpressionLanguageScope;
import org.apache.nifi.flowfile.FlowFile;
import org.apache.nifi.processor.AbstractProcessor;
import org.apache.nifi.processor.ProcessContext;
import org.apache.nifi.processor.ProcessSession;
import org.apache.nifi.processor.ProcessorInitializationContext;
import org.apache.nifi.processor.Relationship;
import org.apache.nifi.processor.exception.ProcessException;
import org.apache.nifi.processor.util.StandardValidators;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Tags({"linux", "journald", "logs", "systemd", "journal"})
@CapabilityDescription("Reads logs from the local systemd journal (journald) on a Linux host.")
@InputRequirement(Requirement.INPUT_FORBIDDEN)
@WritesAttributes({
    @WritesAttribute(attribute = "journald.priority", description = "The priority level of the log entry"),
    @WritesAttribute(attribute = "journald.unit", description = "The systemd unit that generated the log entry"),
    @WritesAttribute(attribute = "journald.timestamp", description = "The timestamp of the log entry"),
    @WritesAttribute(attribute = "journald.hostname", description = "The hostname where the log was generated"),
    @WritesAttribute(attribute = "journald.message", description = "The actual log message"),
    @WritesAttribute(attribute = "mime.type", description = "The mime type of the content (always text/plain)")
})
public class JournaldLogReader extends AbstractProcessor {

    // Define property descriptors
    public static final PropertyDescriptor JOURNAL_UNIT = new PropertyDescriptor.Builder()
            .name("Journal Unit")
            .description("The systemd unit(s) to read logs from. Use '*' for all units.")
            .required(true)
            .defaultValue("*")
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .expressionLanguageSupported(ExpressionLanguageScope.ENVIRONMENT)
            .build();

    public static final PropertyDescriptor PRIORITY_LEVEL = new PropertyDescriptor.Builder()
            .name("Priority Level")
            .description("The minimum priority level of log messages to read")
            .required(true)
            .allowableValues(
                    new AllowableValue("0", "Emergency", "System is unusable"),
                    new AllowableValue("1", "Alert", "Action must be taken immediately"),
                    new AllowableValue("2", "Critical", "Critical conditions"),
                    new AllowableValue("3", "Error", "Error conditions"),
                    new AllowableValue("4", "Warning", "Warning conditions"),
                    new AllowableValue("5", "Notice", "Normal but significant condition"),
                    new AllowableValue("6", "Info", "Informational messages"),
                    new AllowableValue("7", "Debug", "Debug-level messages")
            )
            .defaultValue("6")
            .build();

    public static final PropertyDescriptor POLL_INTERVAL = new PropertyDescriptor.Builder()
            .name("Poll Interval")
            .description("How often to poll the journal for new entries (in seconds)")
            .required(true)
            .defaultValue("1")
            .addValidator(StandardValidators.POSITIVE_INTEGER_VALIDATOR)
            .build();

    public static final PropertyDescriptor BATCH_SIZE = new PropertyDescriptor.Builder()
            .name("Batch Size")
            .description("Maximum number of log entries to include in a single FlowFile")
            .required(true)
            .defaultValue("1000")
            .addValidator(StandardValidators.POSITIVE_INTEGER_VALIDATOR)
            .build();

    public static final PropertyDescriptor JSON_FORMAT = new PropertyDescriptor.Builder()
            .name("Output Format")
            .description("Output format for log entries")
            .required(true)
            .allowableValues(
                    new AllowableValue("JSON", "JSON", "Output logs in JSON format"),
                    new AllowableValue("PLAIN", "Plain Text", "Output logs in plain text format")
            )
            .defaultValue("JSON")
            .build();

    public static final Relationship REL_SUCCESS = new Relationship.Builder()
            .name("success")
            .description("Successfully read log entries will be sent here")
            .build();

    public static final Relationship REL_FAILURE = new Relationship.Builder()
            .name("failure")
            .description("If the processor fails to read logs, empty FlowFiles will be sent here")
            .build();

    private List<PropertyDescriptor> descriptors;
    private Set<Relationship> relationships;
    private final AtomicBoolean isRunning = new AtomicBoolean(false);
    private Process journalProcess;
    private Thread logReaderThread;
    private volatile String lastTimestamp = null;

    public static final PropertyDescriptor USE_TIMESTAMP_FILTERING = new PropertyDescriptor.Builder()
            .name("Use Timestamp Filtering")
            .description("Whether to use timestamp filtering to avoid duplicate logs between runs")
            .required(true)
            .allowableValues("true", "false")
            .defaultValue("true")
            .build();

    @Override
    protected void init(final ProcessorInitializationContext context) {
        final List<PropertyDescriptor> descriptors = new ArrayList<>();
        descriptors.add(JOURNAL_UNIT);
        descriptors.add(PRIORITY_LEVEL);
        descriptors.add(POLL_INTERVAL);
        descriptors.add(BATCH_SIZE);
        descriptors.add(JSON_FORMAT);
        descriptors.add(USE_TIMESTAMP_FILTERING);
        this.descriptors = Collections.unmodifiableList(descriptors);

        final Set<Relationship> relationships = new HashSet<>();
        relationships.add(REL_SUCCESS);
        relationships.add(REL_FAILURE);
        this.relationships = Collections.unmodifiableSet(relationships);
    }

    @Override
    public Set<Relationship> getRelationships() {
        return this.relationships;
    }

    @Override
    public final List<PropertyDescriptor> getSupportedPropertyDescriptors() {
        return descriptors;
    }

    @OnScheduled
    public void onScheduled(final ProcessContext context) {
        isRunning.set(true);
    }

    @OnStopped
    public void onStopped() {
        isRunning.set(false);
        
        // Clean up the journal process if it's running
        if (journalProcess != null) {
            try {
                // Force terminate the process
                journalProcess.destroyForcibly();
                boolean terminated = journalProcess.waitFor(5, TimeUnit.SECONDS);
                if (!terminated) {
                    getLogger().warn("Failed to terminate journalctl process");
                }
            } catch (Exception e) {
                getLogger().error("Error while terminating journalctl process", e);
            } finally {
                journalProcess = null;
            }
        }
        
        // Clean up the reader thread
        if (logReaderThread != null && logReaderThread.isAlive()) {
            logReaderThread.interrupt();
            try {
                logReaderThread.join(5000);
                if (logReaderThread.isAlive()) {
                    getLogger().warn("Reader thread did not terminate gracefully");
                    // In a real-world scenario, you might consider more aggressive thread termination
                    // techniques, though they generally aren't recommended.
                }
            } catch (InterruptedException e) {
                getLogger().warn("Interrupted while waiting for log reader thread to terminate", e);
                Thread.currentThread().interrupt();
            } finally {
                logReaderThread = null;
            }
        }
        
        // Log the shutdown completion
        getLogger().info("JournaldLogReader shutdown completed");
    }

    @Override
    public void onTrigger(final ProcessContext context, final ProcessSession session) throws ProcessException {
        if (!isRunning.get()) {
            return;
        }

        try {
            // Get properties
            final String unit = context.getProperty(JOURNAL_UNIT).getValue();
            final String priority = context.getProperty(PRIORITY_LEVEL).getValue();
            final int batchSize = context.getProperty(BATCH_SIZE).asInteger();
            final String outputFormat = context.getProperty(JSON_FORMAT).getValue();
            final boolean useJson = "JSON".equals(outputFormat);
            final boolean useTimestampFiltering = context.getProperty(USE_TIMESTAMP_FILTERING).asBoolean();
            
            getLogger().debug("Starting JournaldLogReader with unit={}, priority={}, batchSize={}, outputFormat={}, useTimestampFiltering={}",
                    new Object[]{unit, priority, batchSize, outputFormat, useTimestampFiltering});

            // Build the journalctl command
            List<String> command = new ArrayList<>();
            command.add("journalctl");
            
            // Add filters
            if (!"*".equals(unit)) {
                command.add("-u");
                command.add(unit);
            }
            
            command.add("-p");
            command.add(priority);
            
            // Get a reasonable number of log entries on each run
            command.add("-n");
            command.add("100000");
            
            // Add output format
            if (useJson) {
                command.add("-o");
                command.add("json");
            }
            
            // If we have a last timestamp and filtering is enabled, use it to avoid duplicates
            if (useTimestampFiltering && lastTimestamp != null) {
                try {
                    // Convert the microseconds timestamp to a proper ISO time
                    long timestampMicros = Long.parseLong(lastTimestamp);
                    long timestampMillis = timestampMicros / 1000;
                    
                    // Format timestamp in a way journalctl understands
                    String formattedTimestamp = String.format("%1$tY-%1$tm-%1$td %1$tH:%1$tM:%1$tS.%2$06d", 
                        new Date(timestampMillis), timestampMicros % 1000000);
                    
                    command.add("--since");
                    command.add(formattedTimestamp);
                    getLogger().debug("Using timestamp filter: {}", formattedTimestamp);
                } catch (NumberFormatException e) {
                    getLogger().warn("Failed to parse timestamp: {}", lastTimestamp);
                    // Reset the timestamp to avoid continuously failing
                    lastTimestamp = null;
                }
            } else if (!useTimestampFiltering) {
                lastTimestamp = null;
                getLogger().debug("Timestamp filtering disabled");
            }

            // Log the command we're about to execute
            getLogger().debug("Executing command: {}", String.join(" ", command));

            // Start the journalctl process
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            journalProcess = pb.start();

            // Create a reader for the process output
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(journalProcess.getInputStream(), StandardCharsets.UTF_8));

            // Read log entries
            List<String> logEntries = new ArrayList<>();
            String line;
            String currentTimestamp = lastTimestamp;
            int lineCount = 0;
            
            // Set a timeout for reading
            long startTime = System.currentTimeMillis();
            long timeout = 5000; // 5 seconds timeout
            
            while (isRunning.get() && 
                  (System.currentTimeMillis() - startTime < timeout) && 
                  (line = reader.readLine()) != null) {
                
                lineCount++;
                if (line.trim().isEmpty()) {
                    continue;
                }
                
                getLogger().trace("Read line from journalctl: {}", line);
                logEntries.add(line);
                
                // Extract timestamp for tracking
                if (useJson && useTimestampFiltering) {
                    try {
                        // Better JSON parsing to extract timestamp
                        int rtStart = line.indexOf("\"__REALTIME_TIMESTAMP\"");
                        if (rtStart >= 0) {
                            int valueStart = line.indexOf(":", rtStart) + 1;
                            // Find where the numeric value starts
                            while (valueStart < line.length() && 
                                  (line.charAt(valueStart) == ' ' || line.charAt(valueStart) == '"')) {
                                valueStart++;
                            }
                            
                            // Find where the numeric value ends
                            int valueEnd = valueStart;
                            while (valueEnd < line.length() && 
                                  Character.isDigit(line.charAt(valueEnd))) {
                                valueEnd++;
                            }
                            
                            if (valueStart < valueEnd) {
                                currentTimestamp = line.substring(valueStart, valueEnd);
                                getLogger().trace("Extracted timestamp: {}", currentTimestamp);
                            }
                        }
                    } catch (Exception e) {
                        getLogger().warn("Failed to extract timestamp from JSON", e);
                    }
                }
                
                // If we've reached batch size, create a FlowFile and reset
                if (logEntries.size() >= batchSize) {
                    getLogger().debug("Creating FlowFile with {} log entries", logEntries.size());
                    createFlowFile(session, logEntries, useJson);
                    logEntries.clear();
                    if (useTimestampFiltering && currentTimestamp != null && !currentTimestamp.isEmpty()) {
                        lastTimestamp = currentTimestamp;
                    }
                }
            }
            
            // Check if we read anything
            if (lineCount == 0) {
                getLogger().debug("No new log entries found");
            } else {
                getLogger().debug("Read {} lines from journalctl", lineCount);
            }
            
            // Create a FlowFile with any remaining entries
            if (!logEntries.isEmpty()) {
                getLogger().debug("Creating final FlowFile with {} log entries", logEntries.size());
                createFlowFile(session, logEntries, useJson);
                if (useTimestampFiltering && currentTimestamp != null && !currentTimestamp.isEmpty()) {
                    lastTimestamp = currentTimestamp;
                }
            }
            
            // Make sure to clean up the process
            if (journalProcess != null) {
                journalProcess.destroyForcibly();
                journalProcess = null;
            }
            
        } catch (Exception e) {
            getLogger().error("Failed to read from journald", e);
            // Make sure we have a stacktrace for debugging
            getLogger().error("Exception details", e);
            FlowFile flowFile = session.create();
            session.transfer(flowFile, REL_FAILURE);
        }
        
        // Transfer the session
        session.commit();
        
        // Sleep for the poll interval
        try {
            int pollInterval = context.getProperty(POLL_INTERVAL).asInteger();
            getLogger().debug("Sleeping for {} seconds before next poll", pollInterval);
            TimeUnit.SECONDS.sleep(pollInterval);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
    
    private void createFlowFile(ProcessSession session, List<String> logEntries, boolean isJson) {
        if (logEntries.isEmpty()) {
            getLogger().debug("No log entries to create FlowFile with");
            return;
        }
        
        FlowFile flowFile = session.create();
        final StringBuilder sb = new StringBuilder();
        
        for (String entry : logEntries) {
            sb.append(entry).append("\n");
        }
        
        final String content = sb.toString();
        getLogger().debug("Creating FlowFile with {} bytes of content", content.length());
        
        try {
            // Write the content
            flowFile = session.write(flowFile, out -> out.write(content.getBytes(StandardCharsets.UTF_8)));
            
            // Set attributes
            Map<String, String> attributes = new HashMap<>();
            attributes.put("mime.type", "text/plain");
            attributes.put("journald.entries.count", String.valueOf(logEntries.size()));
            attributes.put("journald.timestamp", String.valueOf(System.currentTimeMillis()));
            
            if (isJson) {
                attributes.put("journald.format", "json");
            } else {
                attributes.put("journald.format", "plain");
            }
            
            flowFile = session.putAllAttributes(flowFile, attributes);
            
            // Transfer the FlowFile
            session.transfer(flowFile, REL_SUCCESS);
            getLogger().info("Successfully transferred FlowFile with {} journald entries", logEntries.size());
        } catch (Exception e) {
            getLogger().error("Failed to create or transfer FlowFile", e);
            session.remove(flowFile);
        }
    }
}
