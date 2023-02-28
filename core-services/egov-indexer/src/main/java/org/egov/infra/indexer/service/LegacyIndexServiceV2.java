package org.egov.infra.indexer.service;


import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import lombok.extern.slf4j.Slf4j;
import org.egov.IndexerApplicationRunnerImpl;
import org.egov.infra.indexer.custom.pgr.PGRCustomDecorator;
import org.egov.infra.indexer.custom.pgr.PGRIndexObject;
import org.egov.infra.indexer.custom.pgr.ServiceResponse;
import org.egov.infra.indexer.custom.pt.PTCustomDecorator;
import org.egov.infra.indexer.custom.pt.PropertyResponse;
import org.egov.infra.indexer.models.IndexJob;
import org.egov.infra.indexer.models.IndexJob.StatusEnum;
import org.egov.infra.indexer.models.IndexJobWrapper;
import org.egov.infra.indexer.producer.IndexerProducer;
import org.egov.infra.indexer.util.IndexerUtils;
import org.egov.infra.indexer.util.ResponseInfoFactory;
import org.egov.infra.indexer.web.contract.Index;
import org.egov.infra.indexer.web.contract.LegacyIndexRequest;
import org.egov.infra.indexer.web.contract.LegacyIndexResponse;
import org.egov.infra.indexer.web.contract.Mapping;
import org.egov.infra.indexer.web.contract.Mapping.ConfigKeyEnum;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.web.client.RestTemplate;

import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
public class LegacyIndexServiceV2 {

    @Autowired
    private IndexerApplicationRunnerImpl runner;

    @Autowired
    private RestTemplate restTemplate;

    @Autowired
    private IndexerUtils indexerUtils;

    @Autowired
    private ResponseInfoFactory factory;

    @Autowired
    private IndexerProducer indexerProducer;

    @Value("${egov.core.reindex.topic.name}")
    private String reindexTopic;

    @Value("${egov.core.legacyindex.topic.name}")
    private String legacyIndexTopic;

    @Value("${egov.indexer.persister.create.topic}")
    private String persisterCreate;

    @Value("${egov.indexer.persister.update.topic}")
    private String persisterUpdate;

    @Value("${reindex.pagination.size.default}")
    private Integer defaultPageSizeForReindex;

    @Value("${legacyindex.pagination.size.default}")
    private Integer defaultPageSizeForLegacyindex;

    @Value("${egov.service.host}")
    private String serviceHost;

    @Value("${egov.indexer.pgr.legacyindex.topic.name}")
    private String pgrLegacyTopic;

    @Value("${egov.indexer.pt.legacyindex.topic.name}")
    private String ptLegacyTopic;

    @Value("${egov.infra.indexer.host}")
    private String esHostUrl;

    @Autowired
    private PGRCustomDecorator pgrCustomDecorator;

    @Autowired
    private PTCustomDecorator ptCustomDecorator;

    @Value("${egov.core.no.of.index.threads}")
    private Integer noOfIndexThreads;

    @Value("${egov.core.index.thread.poll.ms}")
    private Long indexThreadPollInterval;

    private ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(5);
    private final ScheduledExecutorService schedulerofChildThreads = Executors.newScheduledThreadPool(1);

    @Autowired
    private IndexerService indexerService;

    /**
     * Creates a legacy index job by making an entry into the eg_indexer_job and returns response with job identifiers.
     *
     * @param legacyindexRequest
     * @return
     */
    public LegacyIndexResponse createLegacyindexJob(LegacyIndexRequest legacyindexRequest) {
        Map<String, Mapping> mappingsMap = runner.getMappingMaps();
        LegacyIndexResponse legacyindexResponse = null;
        StringBuilder url = new StringBuilder();
        Index index = mappingsMap.get(legacyindexRequest.getLegacyIndexTopic()).getIndexes().get(0);
        url.append(esHostUrl).append(index.getName()).append("/").append(index.getType()).append("/_search");
        legacyindexResponse = LegacyIndexResponse.builder()
                .message("Please hit the 'url' after the legacy index job is complete.").url(url.toString())
                .responseInfo(factory.createResponseInfoFromRequestInfo(legacyindexRequest.getRequestInfo(), true))
                .build();
        IndexJob job = IndexJob.builder().jobId(UUID.randomUUID().toString()).jobStatus(StatusEnum.INPROGRESS)
                .typeOfJob(ConfigKeyEnum.LEGACYINDEX)
                .requesterId(legacyindexRequest.getRequestInfo().getUserInfo().getUuid())
                .newIndex(index.getName() + "/" + index.getType()).tenantId(legacyindexRequest.getTenantId())
                .totalRecordsIndexed(0).totalTimeTakenInMS(0L)
                .auditDetails(
                        indexerUtils.getAuditDetails(legacyindexRequest.getRequestInfo().getUserInfo().getUuid(), true))
                .build();
        legacyindexRequest.setJobId(job.getJobId());
        legacyindexRequest.setStartTime(new Date().getTime());
        IndexJobWrapper wrapper = IndexJobWrapper.builder().requestInfo(legacyindexRequest.getRequestInfo()).job(job)
                .build();
//		indexerProducer.producer(legacyIndexTopic, legacyindexRequest);
        beginLegacyIndex(legacyindexRequest);
        indexerProducer.producer(persisterCreate, wrapper);

        legacyindexResponse.setJobId(job.getJobId());

        return legacyindexResponse;
    }

    /**
     * Method to start the index thread for indexing activity
     *
     * @param reindexRequest
     * @return
     */
    public Boolean beginLegacyIndex(LegacyIndexRequest legacyIndexRequest) {
        createLegacyIndexChunkJobs(legacyIndexRequest);
        return true;
    }

    /**
     * Index thread which performs the indexing job. It operates as follows: 1.
     * Based on the Request, it makes API calls in batches to the external service
     * 2. With every batch fetched, data is sent to child threads for processing 3.
     * Child threads perform primary data transformation if required and then hand
     * it over to another esIndexer method 4. The esIndexer method performs checks
     * and transformations pas per the config and then posts the data to es in bulk
     * 5. The process repeats until all the records are indexed.
     *
     * @param reindexRequest
     */
    private void createLegacyIndexChunkJobs(LegacyIndexRequest legacyIndexRequest) {

        log.info("Job Started: " + legacyIndexRequest.getJobId());
        ObjectMapper mapper = indexerUtils.getObjectMapper();
        Integer offset = legacyIndexRequest.getApiDetails().getPaginationDetails().getStartingOffset();
        offset = offset == null ? 0 : offset;
        Integer count = offset;

        // Make module _count call to fetch total number of records for a given tenantId
        Integer totalCount = 75;
        // #############################################################################

        Integer size = null != legacyIndexRequest.getApiDetails().getPaginationDetails().getMaxPageSize()
                ? legacyIndexRequest.getApiDetails().getPaginationDetails().getMaxPageSize()
                : defaultPageSizeForLegacyindex;
        Boolean isProcessDone = false;

        while (offset < totalCount) {
            legacyIndexRequest.getApiDetails().getPaginationDetails().setStartingOffset(offset);
            indexerProducer.producer("legacy-index-chunk-jobs-topic", legacyIndexRequest);
            offset += size;
        }

        if (isProcessDone) {
            IndexJob job = IndexJob.builder().jobId(legacyIndexRequest.getJobId())
                    .auditDetails(indexerUtils.getAuditDetails(
                            legacyIndexRequest.getRequestInfo().getUserInfo().getUuid(), false))
                    .totalRecordsIndexed(count)
                    .totalTimeTakenInMS(new Date().getTime() - legacyIndexRequest.getStartTime())
                    .jobStatus(StatusEnum.COMPLETED).build();
            IndexJobWrapper wrapper = IndexJobWrapper.builder()
                    .requestInfo(legacyIndexRequest.getRequestInfo()).job(job).build();
            indexerProducer.producer(persisterUpdate, wrapper);
        }
    }


    /**
     * Child threads which perform the primary data transformation and pass it on to
     * the esIndexer method
     *
     * @param reindexRequest
     * @param mapper
     * @param requestToReindex
     * @param resultSize
     */
    public void childThreadExecutor(LegacyIndexRequest legacyIndexRequest, ObjectMapper mapper, Object response) {
        try {
            //log.info("childThreadExecutor + response----"+mapper.writeValueAsString(response));
            if (legacyIndexRequest.getLegacyIndexTopic().equals(pgrLegacyTopic)) {
                ServiceResponse serviceResponse = mapper.readValue(mapper.writeValueAsString(response),
                        ServiceResponse.class);
                //PGRIndexObject indexObject = pgrCustomDecorator.dataTransformationForPGR(serviceResponse);
                //log.info("childThreadExecutor + indexObject----"+mapper.writeValueAsString(indexObject));
                indexerProducer.producer(legacyIndexRequest.getLegacyIndexTopic(), serviceResponse);
            } else {
                if (legacyIndexRequest.getLegacyIndexTopic().equals(ptLegacyTopic)) {
                    PropertyResponse propertyResponse = mapper.readValue(mapper.writeValueAsString(response), PropertyResponse.class);
                    propertyResponse.setProperties(ptCustomDecorator.transformData(propertyResponse.getProperties()));
                    indexerProducer.producer(legacyIndexRequest.getLegacyIndexTopic(), propertyResponse);
                } else {
                    //indexerProducer.producer(legacyIndexRequest.getLegacyIndexTopic(), response);
                    indexerService.esIndexer(legacyIndexRequest.getLegacyIndexTopic(), mapper.writeValueAsString(response));
                }
            }
        } catch (Exception e) {
            log.error("Error occurred while processing legacy index", e);
        }
    }

    public void processChunkJob(LegacyIndexRequest legacyIndexRequest) {
        Integer offset = legacyIndexRequest.getApiDetails().getPaginationDetails().getStartingOffset();
        Integer size = legacyIndexRequest.getApiDetails().getPaginationDetails().getMaxPageSize();

        String uri = indexerUtils.buildPagedUriForLegacyIndex(legacyIndexRequest.getApiDetails(),
                offset, size);
        Object request = null;
        try {
            request = legacyIndexRequest.getApiDetails().getRequest();
            if (null == legacyIndexRequest.getApiDetails().getRequest()) {
                HashMap<String, Object> map = new HashMap<>();
                map.put("RequestInfo", legacyIndexRequest.getRequestInfo());
                request = map;
            }
            Object response = restTemplate.postForObject(uri, request, Map.class);
            if (null == response) {
                log.info("Request: " + request);
                log.info("URI: " + uri);
                IndexJob job = IndexJob.builder().jobId(legacyIndexRequest.getJobId())
                        .auditDetails(indexerUtils.getAuditDetails(
                                legacyIndexRequest.getRequestInfo().getUserInfo().getUuid(), false))
                        .totalTimeTakenInMS(new Date().getTime() - legacyIndexRequest.getStartTime())
                        .jobStatus(StatusEnum.FAILED).build();
                IndexJobWrapper wrapper = IndexJobWrapper.builder()
                        .requestInfo(legacyIndexRequest.getRequestInfo()).job(job).build();
                indexerProducer.producer(persisterUpdate, wrapper);
                return;
            } else {
                List<Object> searchResponse = JsonPath.read(response, legacyIndexRequest.getApiDetails().getResponseJsonPath());
                if (!CollectionUtils.isEmpty(searchResponse)) {
                    childThreadExecutor(legacyIndexRequest, indexerUtils.getObjectMapper(), response);
                }
            }
        } catch (Exception e) {
            log.info("JOBFAILED!!! Offset: " + offset + " Size: " + size);
            log.info("Request: " + request);
            log.info("URI: " + uri);
            log.error("Legacy-index Exception: ", e);
            IndexJob job = IndexJob.builder().jobId(legacyIndexRequest.getJobId())
                    .auditDetails(indexerUtils.getAuditDetails(
                            legacyIndexRequest.getRequestInfo().getUserInfo().getUuid(), false))
                    .totalTimeTakenInMS(new Date().getTime() - legacyIndexRequest.getStartTime())
                    .jobStatus(StatusEnum.FAILED).build();
            IndexJobWrapper wrapper = IndexJobWrapper.builder()
                    .requestInfo(legacyIndexRequest.getRequestInfo()).job(job).build();
            indexerProducer.producer(persisterUpdate, wrapper);
            return;
        }

        IndexJob job = IndexJob.builder().jobId(legacyIndexRequest.getJobId())
                .auditDetails(indexerUtils.getAuditDetails(
                        legacyIndexRequest.getRequestInfo().getUserInfo().getUuid(), false))
                .totalTimeTakenInMS(new Date().getTime() - legacyIndexRequest.getStartTime())
                .jobStatus(StatusEnum.INPROGRESS).build();
        IndexJobWrapper wrapper = IndexJobWrapper.builder()
                .requestInfo(legacyIndexRequest.getRequestInfo()).job(job).build();
        indexerProducer.producer(persisterUpdate, wrapper);

    }
}

