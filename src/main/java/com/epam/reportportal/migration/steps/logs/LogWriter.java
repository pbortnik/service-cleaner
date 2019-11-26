package com.epam.reportportal.migration.steps.logs;

import com.epam.reportportal.migration.datastore.binary.DataStoreService;
import com.epam.reportportal.migration.datastore.filesystem.FilePathGenerator;
import com.mongodb.DBObject;
import com.mongodb.gridfs.GridFSDBFile;
import org.apache.tika.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.item.ItemWriter;
import org.springframework.batch.item.database.ItemSqlParameterSourceProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Paths;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.epam.reportportal.migration.datastore.binary.impl.DataStoreUtils.buildThumbnailFileName;
import static com.epam.reportportal.migration.datastore.binary.impl.DataStoreUtils.isImage;
import static com.epam.reportportal.migration.steps.utils.MigrationUtils.toUtc;

/**
 * @author <a href="mailto:pavel_bortnik@epam.com">Pavel Bortnik</a>
 */
@Component
public class LogWriter implements ItemWriter<DBObject> {

	private final Logger LOGGER = LoggerFactory.getLogger(this.getClass());

	private static final String INSERT_LOG = "INSERT INTO log (uuid, log_time, log_message, item_id, last_modified, log_level) "
			+ "VALUES (:uid, :lt, :lmsg, :item, :lm, :ll) ON CONFLICT DO NOTHING";

	private static final String INSERT_LOG_WITH_ATTACH =
			"INSERT INTO log (uuid, log_time, log_message, item_id, last_modified, log_level, attachment_id) "
					+ "VALUES (:uid, :lt, :lmsg, :item, :lm, :ll, :attachId) ON CONFLICT DO NOTHING";

	private static final String INSERT_ATTACH =
			"INSERT INTO attachment (file_id, thumbnail_id, content_type, project_id, launch_id, item_id) "
					+ "VALUES (:fid, :tid, :ct, :pr, :lnch, :item) ON CONFLICT DO NOTHING RETURNING id";

	@Autowired
	@Qualifier("attachmentDataStoreService")
	private DataStoreService dataStoreService;

	@Autowired
	private NamedParameterJdbcTemplate jdbcTemplate;

	@Autowired
	private FilePathGenerator filePathGenerator;

	@Autowired
	private LogWriter logWriter;

	@Override
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void write(List<? extends DBObject> items) {

		Map<Boolean, ? extends List<? extends DBObject>> splitted = items.stream()
				.collect(Collectors.partitioningBy(it -> it.get("file") == null));

		SqlParameterSource[] values = splitted.get(true)
				.stream()
				.map(LOG_SOURCE_PROVIDER::createSqlParameterSource)
				.toArray(SqlParameterSource[]::new);

		try {
			jdbcTemplate.batchUpdate(INSERT_LOG, values);

			splitted.get(false).forEach(logWithBinary -> {
				GridFSDBFile file = (GridFSDBFile) logWithBinary.get("file");

				byte[] bytes;
				try {
					bytes = IOUtils.toByteArray(file.getInputStream());
				} catch (IOException e) {
					throw new RuntimeException(e);
				}

				String commonPath = Paths.get(String.valueOf(logWithBinary.get("projectId")), filePathGenerator.generate()).toString();
				String targetPath = Paths.get(commonPath, file.getFilename()).toString();

				String path = dataStoreService.save(targetPath, new ByteArrayInputStream(bytes));

				String pathThumbnail = createThumbnail(new ByteArrayInputStream(bytes),
						file.getContentType(),
						file.getFilename(),
						commonPath
				);

				Long attachmentId = jdbcTemplate.queryForObject(INSERT_ATTACH,
						attachSourceProvider(logWithBinary, file, path, pathThumbnail),
						Long.class
				);
				MapSqlParameterSource sqlParameterSource = (MapSqlParameterSource) LOG_SOURCE_PROVIDER.createSqlParameterSource(
						logWithBinary);
				sqlParameterSource.addValue("attachId", attachmentId);
				jdbcTemplate.update(INSERT_LOG_WITH_ATTACH, sqlParameterSource);
			});
		} catch (DataIntegrityViolationException e) {
			LOGGER.warn(e.getClass().toString());
			items.forEach(it -> it.put("logMsg", it.get("logMsg").toString().replaceAll("\u0000", "")));
			logWriter.write(items);
		}
	}

	private String createThumbnail(InputStream inputStream, String contentType, String fileName, String commonPath) {
		String thumbnailId = null;
		if (isImage(contentType)) {
			thumbnailId = dataStoreService.saveThumbnail(buildThumbnailFileName(commonPath, fileName), inputStream);
		}
		return thumbnailId;
	}

	private static final ItemSqlParameterSourceProvider<DBObject> LOG_SOURCE_PROVIDER = log -> {
		DBObject level = (DBObject) log.get("level");
		int logLevel = 30000;
		if (level != null) {
			logLevel = (int) level.get("log_level");
		}
		MapSqlParameterSource parameterSource = new MapSqlParameterSource();
		parameterSource.addValue("uid", log.get("_id").toString());
		parameterSource.addValue("lt", toUtc((Date) log.get("logTime")));
		parameterSource.addValue("lmsg", log.get("logMsg"));
		parameterSource.addValue("item", log.get("itemId"));
		parameterSource.addValue("lm", toUtc((Date) log.get("last_modified")));
		parameterSource.addValue("ll", logLevel);
		return parameterSource;
	};

	private MapSqlParameterSource attachSourceProvider(DBObject logFile, GridFSDBFile binary, String filePath, String thumbPath) {
		MapSqlParameterSource parameterSource = new MapSqlParameterSource();
		parameterSource.addValue("fid", filePath);
		parameterSource.addValue("tid", thumbPath);
		parameterSource.addValue("ct", binary.getContentType());
		parameterSource.addValue("pr", logFile.get("projectId"));
		parameterSource.addValue("lnch", logFile.get("launchId"));
		parameterSource.addValue("item", logFile.get("itemId"));
		return parameterSource;
	}

	;

}
