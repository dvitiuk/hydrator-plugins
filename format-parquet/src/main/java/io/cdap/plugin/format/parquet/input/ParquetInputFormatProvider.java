/*
 * Copyright © 2018-2021 Cask Data, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package io.cdap.plugin.format.parquet.input;

import com.google.common.base.Strings;
import io.cdap.cdap.api.annotation.Description;
import io.cdap.cdap.api.annotation.Macro;
import io.cdap.cdap.api.annotation.Name;
import io.cdap.cdap.api.annotation.Plugin;
import io.cdap.cdap.api.data.schema.Schema;
import io.cdap.cdap.api.plugin.PluginClass;
import io.cdap.cdap.etl.api.validation.ValidatingInputFormat;
import io.cdap.plugin.common.batch.JobUtils;
import io.cdap.plugin.format.input.PathTrackingConfig;
import io.cdap.plugin.format.input.PathTrackingInputFormatProvider;
import org.apache.avro.generic.GenericData;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.mapreduce.Job;
import org.apache.parquet.avro.AvroParquetReader;
import org.apache.parquet.hadoop.ParquetReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * Provides and sets up configuration for an parquet input format.
 */
@Plugin(type = ValidatingInputFormat.PLUGIN_TYPE)
@Name(ParquetInputFormatProvider.NAME)
@Description(ParquetInputFormatProvider.DESC)
public class ParquetInputFormatProvider extends PathTrackingInputFormatProvider<ParquetInputFormatProvider.Conf> {
  private static final Logger LOG = LoggerFactory.getLogger(PathTrackingInputFormatProvider.class);
  static final String NAME = "parquet";
  static final String DESC = "Plugin for reading files in text format.";
  public static final PluginClass PLUGIN_CLASS =
    new PluginClass(ValidatingInputFormat.PLUGIN_TYPE, NAME, DESC, ParquetInputFormatProvider.class.getName(),
                    "conf", PathTrackingConfig.FIELDS);

  public ParquetInputFormatProvider(ParquetInputFormatProvider.Conf conf) {
    super(conf);
  }

  @Override
  public String getInputFormatClassName() {
    return CombineParquetInputFormat.class.getName();
  }

  @Override
  protected void addFormatProperties(Map<String, String> properties) {
    Schema schema = conf.getSchema();
    if (schema != null) {
      properties.put("parquet.avro.read.schema", schema.toString());
    }
  }

  /**
   * Common config for Parquet format
   */
  public static class Conf extends PathTrackingConfig {

    @Macro
    @Nullable
    @Description(NAME_SCHEMA)
    public String schema;

    @Override
    public Schema getSchema() {
      if (containsMacro(NAME_SCHEMA)) {
        return null;
      }
      if (Strings.isNullOrEmpty(schema)) {
        try {
          String lengthFieldResolved = null;
          String modificationTimeFieldResolved = null;

          // this is required for back compatibility with File-based sources (File, FTP...)
          try {
            lengthFieldResolved = lengthField;
            modificationTimeFieldResolved = modificationTimeField;
          } catch (NoSuchFieldError e) {
            LOG.warn("A modern ParquetInputFormatProvider is used with an old plugin.");
          }
          return getDefaultSchema(pathField, lengthFieldResolved, modificationTimeFieldResolved);
        } catch (Exception e) {
          throw new IllegalArgumentException("Invalid schema: " + e.getMessage(), e);
        }
      }
      try {
        return Schema.parseJson(schema);
      } catch (IOException e) {
        throw new IllegalArgumentException("Invalid schema: " + e.getMessage(), e);
      }
    }

    /**
     * Extract schema from file
     *
     * @return {@link Schema}
     * @throws IOException raised when error occurs during schema extraction
     */
    public Schema getDefaultSchema(@Nullable String pathField, @Nullable String lengthField,
                                   @Nullable String modificationTimeField) throws Exception {
      String filePath = getProperties().getProperties().getOrDefault("path", null);
      ParquetReader reader = null;
      try {
        Job job = JobUtils.createInstance();
        Configuration hconf = job.getConfiguration();
        // set entries here, before FileSystem is used
        for (Map.Entry<String, String> entry : getFileSystemProperties().entrySet()) {
          hconf.set(entry.getKey(), entry.getValue());
        }
        final Path file = getFilePathForSchemaGeneration(filePath, ".+\\.parquet", hconf, job);
        reader = AvroParquetReader.builder(file).build();
        GenericData.Record record = (GenericData.Record) reader.read();

        Schema schema = Schema.parseJson(record.getSchema().toString());
        List<Schema.Field> fields = new ArrayList<>(schema.getFields());

        if (pathField != null && !pathField.isEmpty()) {
          fields.add(Schema.Field.of(pathField, Schema.of(Schema.Type.STRING)));
        }
        if (lengthField != null && !lengthField.isEmpty()) {
          fields.add(Schema.Field.of(lengthField, Schema.of(Schema.Type.LONG)));
        }
        if (modificationTimeField != null && !modificationTimeField.isEmpty()) {
          fields.add(Schema.Field.of(modificationTimeField, Schema.of(Schema.Type.LONG)));
        }

        return Schema.recordOf("record", fields);
      } finally {
        if (reader != null) {
          reader.close();
        }
      }
    }
  }
}
