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

package io.cdap.plugin.format.input;

import com.google.common.base.Strings;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import io.cdap.cdap.api.annotation.Description;
import io.cdap.cdap.api.annotation.Macro;
import io.cdap.cdap.api.data.schema.Schema;
import io.cdap.cdap.api.plugin.PluginConfig;
import io.cdap.cdap.api.plugin.PluginPropertyField;
import io.cdap.plugin.common.batch.JobUtils;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.mapreduce.Job;

import java.io.IOException;
import java.lang.reflect.Type;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.annotation.Nullable;

/**
 * Plugin config for input format plugins that can track the path of the file that each record was read from.
 */
public class PathTrackingConfig extends PluginConfig {
  public static final Map<String, PluginPropertyField> FIELDS;
  public static final String NAME_SCHEMA = "schema";
  private static final String SCHEMA_DESC = "Schema of the data to read.";
  private static final String PATH_FIELD_DESC =
    "Output field to place the path of the file that the record was read from. "
      + "If not specified, the file path will not be included in output records. "
      + "If specified, the field must exist in the schema and be of type string.";
  private static final String LENGTH_FIELD_DESC =
    "Output field to place the length of the file that the record was read from. "
      + "If not specified, the file length will not be included in output records. "
      + "If specified, the field must exist in the schema and be of type long.";
  private static final String MODIFICATION_TIME_FIELD_DESC =
    "Output field to place the modification time of the file that the record was read from. "
      + "If not specified, the file modification time will not be included in output records. "
      + "If specified, the field must exist in the schema and be of type long.";
  private static final String FILENAME_ONLY_DESC =
    "Whether to only use the filename instead of the URI of the file path when a path field is given. "
      + "The default value is false.";
  private static final String FILE_SYSTEM_PROPERTIES = "fileSystemProperties";
  private static final Gson GSON = new Gson();
  private static final Type MAP_STRING_STRING_TYPE = new TypeToken<Map<String, String>>() {
  }.getType();

  static {
    Map<String, PluginPropertyField> fields = new HashMap<>();
    fields.put("schema", new PluginPropertyField("schema", SCHEMA_DESC, "string", false, true));
    fields.put("pathField",
               new PluginPropertyField("pathField", PATH_FIELD_DESC, "string", false, true));
    fields.put("lengthField",
               new PluginPropertyField("lengthField", LENGTH_FIELD_DESC, "string", false, true));
    fields.put("modificationTimeField",
               new PluginPropertyField("modificationTimeField", MODIFICATION_TIME_FIELD_DESC, "string", false, true));
    fields.put("filenameOnly",
               new PluginPropertyField("filenameOnly", FILENAME_ONLY_DESC, "boolean", false, true));
    FIELDS = Collections.unmodifiableMap(fields);
  }

  @Macro
  @Nullable
  @Description(SCHEMA_DESC)
  protected String schema;

  @Macro
  @Nullable
  @Description(PATH_FIELD_DESC)
  protected String pathField;

  @Macro
  @Nullable
  @Description(LENGTH_FIELD_DESC)
  protected String lengthField;

  @Macro
  @Nullable
  @Description(MODIFICATION_TIME_FIELD_DESC)
  protected String modificationTimeField;

  @Macro
  @Nullable
  @Description(FILENAME_ONLY_DESC)
  protected Boolean filenameOnly;

  @Nullable
  public String getPathField() {
    return pathField;
  }

  @Nullable
  public String getLengthField() {
    return lengthField;
  }

  @Nullable
  public String getModificationTimeField() {
    return modificationTimeField;
  }

  public boolean useFilenameOnly() {
    return filenameOnly == null ? false : filenameOnly;
  }

  @Nullable
  public Schema getSchema() {
    try {
      return Strings.isNullOrEmpty(schema) ? null : Schema.parseJson(schema);
    } catch (IOException e) {
      throw new IllegalArgumentException("Invalid schema: " + e.getMessage(), e);
    }
  }


  /**
   * Checks whether provided path is directory or file and returns file based on the following
   * conditions: if provided path directs to file - file from the provided path will be returned if
   * provided path directs to a directory - first file matching the extension will be provided if
   * extension is null first file from the directory will be returned
   *
   * @param path path from config
   * @param regexPathFilter the regex used to filter the files
   * @param job job to retrieve the file system
   * @return {@link Path}
   */
  public Path getFilePathForSchemaGeneration(String path, String regexPathFilter, Configuration configuration, Job job)
    throws IOException {
    Path fsPath = new Path(path);
    // need this to load the extra class loader to avoid ClassNotFoundException for the file system
    FileSystem fs = JobUtils.applyWithExtraClassLoader(job, getClass().getClassLoader(),
                                                       f -> FileSystem.get(fsPath.toUri(), configuration));

    if (!fs.exists(fsPath)) {
      throw new IOException("Input path not found");
    }

    if (fs.isFile(fsPath)) {
      return fsPath;
    }

    final FileStatus[] files = fs.listStatus(fsPath);

    if (files == null) {
      throw new IllegalArgumentException("Cannot read files from provided path");
    }

    if (files.length == 0) {
      throw new IllegalArgumentException("Provided directory is empty");
    }

    for (FileStatus file : files) {
      if (Strings.isNullOrEmpty(regexPathFilter)) {
        return file.getPath();
      } else {
        Pattern pattern = Pattern.compile(regexPathFilter);
        Matcher matcher = pattern.matcher(file.getPath().toString());
        if (matcher.find()) {
          return file.getPath();
        }
      }
    }
    throw new IllegalArgumentException(String.format("No file inside \"%s\" matched regex \"%s\"!", path,
                                                     regexPathFilter));
  }

  /**
   * Read file system properties from config
   *
   * @return
   */
  public Map<String, String> getFileSystemProperties() {
    if (getRawProperties().getProperties().containsKey(FILE_SYSTEM_PROPERTIES)) {
      return GSON.fromJson(getRawProperties().getProperties().get(FILE_SYSTEM_PROPERTIES), MAP_STRING_STRING_TYPE);
    }
    return Collections.emptyMap();
  }
}
