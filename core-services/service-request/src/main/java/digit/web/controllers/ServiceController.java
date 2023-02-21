package digit.web.controllers;

import digit.service.ServiceRequestService;
import digit.util.ResponseInfoFactory;
import digit.web.models.*;
import lombok.extern.slf4j.Slf4j;
import org.egov.common.contract.request.RequestInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.egov.common.contract.response.ResponseInfo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Slf4j
@RestController
@RequestMapping("/service")
public class ServiceController {

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ResponseInfoFactory responseInfoFactory;

    @Autowired
    private ServiceRequestService serviceRequestService;

    @RequestMapping(value="/v1/_create", method = RequestMethod.POST)
    public ResponseEntity<ServiceResponse> create(@RequestBody @Valid ServiceRequest serviceRequest) {
        Service service = serviceRequestService.createService(serviceRequest);
        ResponseInfo responseInfo = responseInfoFactory.createResponseInfoFromRequestInfo(serviceRequest.getRequestInfo(), true);
        ServiceResponse response = ServiceResponse.builder().service(Collections.singletonList(service)).responseInfo(responseInfo).build();
        return new ResponseEntity<>(response, HttpStatus.OK);
    }

    @RequestMapping(value="/v1/_search", method = RequestMethod.POST)
    public ResponseEntity<ServiceDefinitionResponse> search(@Valid @RequestBody ServiceDefinitionSearchRequest serviceDefinitionSearchRequest) {
        List<ServiceDefinition> serviceDefinitionList = serviceRequestService.searchServiceDefinition(serviceDefinitionSearchRequest);
        ServiceDefinitionResponse response  = ServiceDefinitionResponse.builder().serviceDefinition(serviceDefinitionList).build();
        return new ResponseEntity<>(response,HttpStatus.OK);
    }

    @RequestMapping(value="/v1/_update", method = RequestMethod.POST)
    public ResponseEntity<ServiceDefinitionResponse> update(@RequestBody @Valid ServiceDefinitionRequest serviceDefinitionRequest){
        ServiceDefinition serviceDefinition = serviceRequestService.updateServiceDefinition(serviceDefinitionRequest);
        ResponseInfo responseInfo = responseInfoFactory.createResponseInfoFromRequestInfo(serviceDefinitionRequest.getRequestInfo(), true);
        ServiceDefinitionResponse response = ServiceDefinitionResponse.builder().serviceDefinition(Collections.singletonList(serviceDefinition)).responseInfo(responseInfo).build();
        return new ResponseEntity<>(response, HttpStatus.OK);
    }

}
