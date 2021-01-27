package com.external.plugins;

import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.auth.profile.ProfileCredentialsProvider;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.dynamodbv2.xspec.S;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;

import com.amazonaws.services.s3.model.ListObjectsV2Result;
import com.amazonaws.services.s3.model.ObjectListing;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.s3.model.S3ObjectSummary;
import com.amazonaws.services.s3.transfer.TransferManager;
import com.amazonaws.services.s3.transfer.TransferManagerBuilder;
import com.appsmith.external.models.ActionConfiguration;
import com.appsmith.external.models.ActionExecutionResult;
import com.appsmith.external.models.Connection;
import com.appsmith.external.models.DBAuth;
import com.appsmith.external.models.DatasourceConfiguration;
import com.appsmith.external.models.DatasourceStructure;
import com.appsmith.external.models.DatasourceTestResult;
import com.appsmith.external.models.Endpoint;
import com.appsmith.external.models.Property;
import com.appsmith.external.models.SSLDetails;
import com.appsmith.external.pluginExceptions.AppsmithPluginError;
import com.appsmith.external.pluginExceptions.AppsmithPluginException;
import com.appsmith.external.plugins.BasePlugin;
import com.appsmith.external.plugins.PluginExecutor;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang.ObjectUtils;
import org.apache.commons.lang.StringUtils;
import org.pf4j.Extension;
import org.pf4j.PluginWrapper;
import org.springframework.util.CollectionUtils;
import reactor.core.Exceptions;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import static com.appsmith.external.models.Connection.Mode.READ_ONLY;

public class S3Plugin extends BasePlugin {

    private static final String S3_DRIVER = "com.amazonaws.services.s3.AmazonS3";

    public S3Plugin(PluginWrapper wrapper) {
        super(wrapper);
    }

    @Slf4j
    @Extension
    public static class S3PluginExecutor implements PluginExecutor<AmazonS3> {
        private final Scheduler scheduler = Schedulers.elastic();

        ArrayList<String> getFilenamesFromObjectListing(ObjectListing objectListing) {
            ArrayList<String> result = new ArrayList<>();
            List<S3ObjectSummary> objects = objectListing.getObjectSummaries();
            for (S3ObjectSummary os : objects) {
                result.add(os.getKey());
            }

            return result;
        }

        ArrayList<String> listAllFilesInBucket(AmazonS3 connection, String bucketName) {
            ArrayList<String> fileList = new ArrayList<>();
            ObjectListing result = connection.listObjects(bucketName);
            fileList.addAll(getFilenamesFromObjectListing(result));

            while(result.isTruncated()) {
                result = connection.listNextBatchOfObjects(result);
                fileList.addAll(getFilenamesFromObjectListing(result));
            }

            return fileList;
        }

        /*
         * - Exception thrown here is handled by the caller.
         */
        boolean uploadFileFromBody(AmazonS3 connection,
                                   String bucketName,
                                   String path,
                                   String body)
                                   throws InterruptedException {
            InputStream inputStream = new ByteArrayInputStream(body.getBytes());
            TransferManager transferManager = TransferManagerBuilder.standard().withS3Client(connection).build();
            transferManager.upload(bucketName, path, inputStream, new ObjectMetadata()).waitForUploadResult();

            return true;
        }

        @Override
        public Mono<ActionExecutionResult> execute(AmazonS3 connection,
                                                   DatasourceConfiguration datasourceConfiguration,
                                                   ActionConfiguration actionConfiguration) {
            /*
             * AmazonS3 API collection does not seem to provide any API to test connection validity or staleness.
             * Hence, unable to do stale connection check.
             */

            //TODO: add checks.
            final List<Map<String, Object>> rowsList = new ArrayList<>(50);
            final String path = actionConfiguration.getPath();

            //TODO: make this check conditional.
            /*if (StringUtils.isBlank(path)) {
                return Mono.error(new AppsmithPluginException(
                        AppsmithPluginError.PLUGIN_ERROR,
                        "Document/Collection path cannot be empty"
                ));
            }*/

            final List<Property> properties = actionConfiguration.getPluginSpecifiedTemplates();
            final com.external.plugins.S3Action s3Action = CollectionUtils.isEmpty(properties)
                                                           ? null
                                                           : com.external.plugins.S3Action
                                                           .valueOf(properties.get(0).getValue());
            if (s3Action == null) {
                return Mono.error(new AppsmithPluginException(
                        AppsmithPluginError.PLUGIN_ERROR,
                        "Missing S3 action."
                ));
            }

            final String bucketName = CollectionUtils.isEmpty(properties)
                                      ? null
                                      : properties.get(1).getValue();
            if (bucketName == null) {
                return Mono.error(new AppsmithPluginException(
                        AppsmithPluginError.PLUGIN_ERROR,
                        "Missing bucket name."
                ));
            }

            final String body = actionConfiguration.getBody();

            if (body == null || StringUtils.isBlank(body)) {
                //TODO: report error
            }

            //TODO: handle error
            return Mono.fromCallable(() -> {
                switch (s3Action) {
                    case LIST:
                        ArrayList<String> listOfFiles = listAllFilesInBucket(connection, bucketName);
                        for(int i=0; i<listOfFiles.size(); i++) {
                            rowsList.add(Map.of("List of Files", listOfFiles.get(i)));
                        }
                        break;
                    case UPLOAD_FILE_FROM_BODY:
                         uploadFileFromBody(connection, bucketName, path, body);
                         rowsList.add(Map.of("Action Status", "File uploaded successfully"));
                         break;
                    case DELETE_FILE:
                        connection.deleteObject(bucketName, path);
                        rowsList.add(Map.of("Action Status", "File deleted successfully"));
                        break;
                    default:
                        throw Exceptions.propagate(
                                new AppsmithPluginException(
                                    AppsmithPluginError.PLUGIN_ERROR,
                                    "Invalid S3 action: " + s3Action.toString()
                                )
                        );
                }

                return rowsList;
            })
            .flatMap(result -> {
                ActionExecutionResult actionExecutionResult = new ActionExecutionResult();
                actionExecutionResult.setBody(objectMapper.valueToTree(rowsList));
                actionExecutionResult.setIsExecutionSuccess(true);
                System.out.println(
                        Thread.currentThread().getName()
                                + ": In the S3 Plugin, got action execution result");
                return Mono.just(actionExecutionResult);
            });
        }

        @Override
        public Mono<AmazonS3> datasourceCreate(DatasourceConfiguration datasourceConfiguration) {
            if(datasourceConfiguration == null) {
                return Mono.error(
                  new AppsmithPluginException(
                          AppsmithPluginError.PLUGIN_ERROR,
                          "datasource configuration is null"
                  )
                );
            }

            try {
                Class.forName(S3_DRIVER);
            } catch (ClassNotFoundException e) {
                return Mono.error(
                        new AppsmithPluginException(
                            AppsmithPluginError.PLUGIN_ERROR,
                            "Error loading S3 driver class."
                        )
                );
            }

            List<Property> properties = datasourceConfiguration.getProperties();
            if (properties == null || properties.isEmpty()) {
                return Mono.error(
                        new AppsmithPluginException(
                                AppsmithPluginError.PLUGIN_ERROR,
                                "Datasource not configured properly"
                        )
                );
            }

            return Mono.fromCallable(() -> {
                Regions clientRegion = null;
                try {
                    clientRegion = Regions.fromName(properties.get(0).getValue());
                } catch (Exception e) {
                    throw Exceptions.propagate(
                            new AppsmithPluginException(
                                    AppsmithPluginError.PLUGIN_ERROR,
                                    "Error when parsing region information from datasource configuration: "
                                    + e.getMessage()
                            )
                    );
                }

                DBAuth authentication = (DBAuth) datasourceConfiguration.getAuthentication();
                if(authentication == null) {
                    throw Exceptions.propagate(
                            new AppsmithPluginException(
                                    AppsmithPluginError.PLUGIN_ERROR,
                                    "Datasource configuration authentication property is null"
                            )
                    );
                }

                String accessKey = authentication.getUsername();
                String secretKey = authentication.getPassword();
                BasicAWSCredentials awsCreds = null;
                try {
                    awsCreds = new BasicAWSCredentials(accessKey, secretKey);
                } catch (Exception e) {
                    throw Exceptions.propagate(
                            new AppsmithPluginException(
                                    AppsmithPluginError.PLUGIN_ERROR,
                                    "Error when creating AWS credentials from datasource credentials: " + e.getMessage()
                            )
                    );
                }

                return AmazonS3ClientBuilder
                                .standard()
                                .withRegion(clientRegion)
                                .withCredentials(new AWSStaticCredentialsProvider(awsCreds))
                                .build();
            })
            .onErrorResume(e -> {
                        if(e instanceof AppsmithPluginException) {
                            return Mono.error(e);
                        }

                        return Mono.error(
                                new AppsmithPluginException(
                                        AppsmithPluginError.PLUGIN_ERROR,
                                        "Error connecting to Redshift: " + e.getMessage()
                                )
                        );
                    }
            )
            .subscribeOn(scheduler);
        }

        @Override
        public void datasourceDestroy(AmazonS3 connection) {
            if (connection != null) {
                Mono.fromCallable(() -> {
                        connection.shutdown();
                        return connection;
                    })
                    .onErrorResume(exception -> {
                        log.debug("In datasourceDestroy function error mode.", exception);
                        System.out.println("In datasourceDestroy function error mode: " + exception);
                        return Mono.empty();
                    })
                    .subscribeOn(scheduler)
                    .subscribe();
            }
        }

        @Override
        public Set<String> validateDatasource(DatasourceConfiguration datasourceConfiguration) {
            Set<String> invalids = new HashSet<>();

            if(datasourceConfiguration == null) {
                invalids.add("datasource configuration is null.");
            }

            if (datasourceConfiguration.getAuthentication() == null) {
                invalids.add("Missing authentication details.");

            } else {
                DBAuth authentication = (DBAuth) datasourceConfiguration.getAuthentication();
                if (org.springframework.util.StringUtils.isEmpty(authentication.getUsername())) {
                    invalids.add("Missing Access Key for authentication.");
                }

                if (org.springframework.util.StringUtils.isEmpty(authentication.getPassword())) {
                    invalids.add("Missing Secret Key for authentication.");
                }
            }

            List<Property> properties = datasourceConfiguration.getProperties();
            if(properties == null) {
                invalids.add("datasource configuration properties list is null.");
            }

            if(CollectionUtils.isEmpty(properties)) {
                invalids.add("Missing Region info for authentication");
            }
            else {
                String region = properties.get(0).getValue();

                if(region == null) {
                    invalids.add("Region info is null");
                }
            }

            return invalids;
        }

        @Override
        public Mono<DatasourceTestResult> testDatasource(DatasourceConfiguration datasourceConfiguration) {
            if(datasourceConfiguration == null) {
                return Mono.just(new DatasourceTestResult("Datasource configuration is null"));
            }

            return datasourceCreate(datasourceConfiguration)
                    .map(connection -> {
                        try {
                            if (connection != null) {
                                connection.shutdown();
                            }
                        } catch (Exception e) {
                            log.warn("Error closing S3 connection that was made for testing.", e);
                        }

                        return new DatasourceTestResult();
                    })
                    .onErrorResume(error -> Mono.just(new DatasourceTestResult(error.getMessage())));
        }

        @Override
        public Mono<DatasourceStructure> getStructure(AmazonS3 connection, DatasourceConfiguration datasourceConfiguration) {
            /*
             * Not sure if it make sense to list all buckets as part of structure ? Leaving it empty for now.
             */
            return null;
        }
    }
}