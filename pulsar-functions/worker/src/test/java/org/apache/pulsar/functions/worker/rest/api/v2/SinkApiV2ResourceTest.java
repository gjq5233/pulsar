/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.pulsar.functions.worker.rest.api.v2;

import com.google.gson.Gson;
import lombok.extern.slf4j.Slf4j;
import org.apache.distributedlog.api.namespace.Namespace;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.config.Configurator;
import org.apache.pulsar.common.policies.data.ErrorData;
import org.apache.pulsar.common.util.FutureUtil;
import org.apache.pulsar.functions.api.Record;
import org.apache.pulsar.functions.proto.Function.FunctionDetails;
import org.apache.pulsar.functions.proto.Function.FunctionMetaData;
import org.apache.pulsar.functions.runtime.RuntimeFactory;
import org.apache.pulsar.functions.source.TopicSchema;
import org.apache.pulsar.functions.utils.SinkConfig;
import org.apache.pulsar.functions.utils.SinkConfigUtils;
import org.apache.pulsar.functions.worker.*;
import org.apache.pulsar.functions.worker.request.RequestResult;
import org.apache.pulsar.functions.worker.rest.api.FunctionsImpl;
import org.apache.pulsar.io.core.Sink;
import org.apache.pulsar.io.core.SinkContext;
import org.glassfish.jersey.media.multipart.FormDataContentDisposition;
import org.powermock.core.classloader.annotations.PowerMockIgnore;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.testng.Assert;
import org.testng.IObjectFactory;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.ObjectFactory;
import org.testng.annotations.Test;

import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyBoolean;
import static org.mockito.Matchers.anyObject;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;
import static org.powermock.api.mockito.PowerMockito.doNothing;
import static org.powermock.api.mockito.PowerMockito.doReturn;
import static org.powermock.api.mockito.PowerMockito.doThrow;
import static org.powermock.api.mockito.PowerMockito.*;
import static org.testng.Assert.assertEquals;

/**
 * Unit test of {@link SinkApiV2Resource}.
 */
@PrepareForTest({Utils.class, SinkConfigUtils.class})
@PowerMockIgnore({ "javax.management.*", "javax.ws.*", "org.apache.logging.log4j.*" })
@Slf4j
public class SinkApiV2ResourceTest {

    @ObjectFactory
    public IObjectFactory getObjectFactory() {
        return new org.powermock.modules.testng.PowerMockObjectFactory();
    }

    private static final class TestSink implements Sink<String> {

        @Override public void open(final Map<String, Object> config, SinkContext sinkContext) {
        }

        @Override public void write(Record<String> record) { }

        @Override public void close() { }
    }

    private static final String tenant = "test-tenant";
    private static final String namespace = "test-namespace";
    private static final String sink = "test-sink";
    private static final Map<String, String> topicsToSerDeClassName = new HashMap<>();
    static {
        topicsToSerDeClassName.put("persistent://sample/standalone/ns1/test_src", TopicSchema.DEFAULT_SERDE);
    }
    private static final String subscriptionName = "test-subscription";
    private static final String className = TestSink.class.getName();
    private static final String serde = TopicSchema.DEFAULT_SERDE;
    private static final int parallelism = 1;

    private WorkerService mockedWorkerService;
    private FunctionMetaDataManager mockedManager;
    private FunctionRuntimeManager mockedFunctionRunTimeManager;
    private RuntimeFactory mockedRuntimeFactory;
    private Namespace mockedNamespace;
    private FunctionsImpl resource;
    private InputStream mockedInputStream;
    private FormDataContentDisposition mockedFormData;

    @BeforeMethod
    public void setup() throws Exception {
        this.mockedManager = mock(FunctionMetaDataManager.class);
        this.mockedFunctionRunTimeManager = mock(FunctionRuntimeManager.class);
        this.mockedRuntimeFactory = mock(RuntimeFactory.class);
        this.mockedInputStream = mock(InputStream.class);
        this.mockedNamespace = mock(Namespace.class);
        this.mockedFormData = mock(FormDataContentDisposition.class);
        when(mockedFormData.getFileName()).thenReturn("test");

        this.mockedWorkerService = mock(WorkerService.class);
        when(mockedWorkerService.getFunctionMetaDataManager()).thenReturn(mockedManager);
        when(mockedWorkerService.getFunctionRuntimeManager()).thenReturn(mockedFunctionRunTimeManager);
        when(mockedFunctionRunTimeManager.getRuntimeFactory()).thenReturn(mockedRuntimeFactory);
        when(mockedWorkerService.getDlogNamespace()).thenReturn(mockedNamespace);
        when(mockedWorkerService.isInitialized()).thenReturn(true);

        // worker config
        WorkerConfig workerConfig = new WorkerConfig()
            .setWorkerId("test")
            .setWorkerPort(8080)
            .setDownloadDirectory("/tmp/pulsar/functions")
            .setFunctionMetadataTopicName("pulsar/functions")
            .setNumFunctionPackageReplicas(3)
            .setPulsarServiceUrl("pulsar://localhost:6650/");
        when(mockedWorkerService.getWorkerConfig()).thenReturn(workerConfig);

        this.resource = spy(new FunctionsImpl(() -> mockedWorkerService));
        doReturn(null).when(resource).extractNarClassLoader(anyString(), anyString(), anyObject(), anyBoolean());
        mockStatic(SinkConfigUtils.class);
        when(SinkConfigUtils.convert(anyObject(), anyObject())).thenReturn(FunctionDetails.newBuilder().build());
    }

    //
    // Register Functions
    //

    @Test
    public void testRegisterSinkMissingTenant() throws IOException {
        testRegisterSinkMissingArguments(
            null,
            namespace,
                sink,
            mockedInputStream,
            mockedFormData,
            topicsToSerDeClassName,
            className,
            parallelism,
                "Tenant is not provided");
    }

    @Test
    public void testRegisterSinkMissingNamespace() throws IOException {
        testRegisterSinkMissingArguments(
            tenant,
            null,
                sink,
            mockedInputStream,
            mockedFormData,
            topicsToSerDeClassName,
            className,
            parallelism,
                "Namespace is not provided");
    }

    @Test
    public void testRegisterSinkMissingFunctionName() throws IOException {
        testRegisterSinkMissingArguments(
            tenant,
            namespace,
            null,
            mockedInputStream,
            mockedFormData,
            topicsToSerDeClassName,
            className,
            parallelism,
                "Function Name is not provided");
    }

    @Test
    public void testRegisterSinkMissingPackage() throws IOException {
        testRegisterSinkMissingArguments(
            tenant,
            namespace,
                sink,
            null,
            mockedFormData,
            topicsToSerDeClassName,
            className,
            parallelism,
                "Function Package is not provided");
    }

    @Test
    public void testRegisterSinkMissingPackageDetails() throws IOException {
        testRegisterSinkMissingArguments(
            tenant,
            namespace,
                sink,
            mockedInputStream,
            null,
            topicsToSerDeClassName,
            className,
            parallelism,
                "Function Package is not provided");
    }

    private void testRegisterSinkMissingArguments(
            String tenant,
            String namespace,
            String sink,
            InputStream inputStream,
            FormDataContentDisposition details,
            Map<String, String> inputTopicMap,
            String className,
            Integer parallelism,
            String errorExpected) throws IOException {
        SinkConfig sinkConfig = new SinkConfig();
        if (tenant != null) {
            sinkConfig.setTenant(tenant);
        }
        if (namespace != null) {
            sinkConfig.setNamespace(namespace);
        }
        if (sink != null) {
            sinkConfig.setName(sink);
        }
        if (inputTopicMap != null) {
            sinkConfig.setTopicToSerdeClassName(inputTopicMap);
        }
        if (className != null) {
            sinkConfig.setClassName(className);
        }
        if (parallelism != null) {
            sinkConfig.setParallelism(parallelism);
        }

        Response response = resource.registerFunction(
                tenant,
                namespace,
                sink,
                inputStream,
                details,
                null,
                null,
                null,
                null,
                new Gson().toJson(sinkConfig),
                null);

        assertEquals(Status.BAD_REQUEST.getStatusCode(), response.getStatus());
        Assert.assertEquals(((ErrorData) response.getEntity()).reason, new ErrorData(errorExpected).reason);
    }

    private Response registerDefaultSink() {
        SinkConfig sinkConfig = new SinkConfig();
        sinkConfig.setTenant(tenant);
        sinkConfig.setNamespace(namespace);
        sinkConfig.setName(sink);
        sinkConfig.setClassName(className);
        sinkConfig.setParallelism(parallelism);
        sinkConfig.setTopicToSerdeClassName(topicsToSerDeClassName);
        return resource.registerFunction(
            tenant,
            namespace,
                sink,
            mockedInputStream,
            mockedFormData,
            null,
            null,
            null,
            null,
            new Gson().toJson(sinkConfig),
                null);
    }

    @Test
    public void testRegisterExistedSink() throws IOException {
        Configurator.setRootLevel(Level.DEBUG);

        when(mockedManager.containsFunction(eq(tenant), eq(namespace), eq(sink))).thenReturn(true);

        Response response = registerDefaultSink();
        assertEquals(Status.BAD_REQUEST.getStatusCode(), response.getStatus());
        assertEquals(new ErrorData("Function " + sink + " already exists").reason, ((ErrorData) response.getEntity()).reason);
    }

    @Test
    public void testRegisterSinkUploadFailure() throws Exception {
        mockStatic(Utils.class);
        doThrow(new IOException("upload failure")).when(Utils.class);
        Utils.uploadToBookeeper(
            any(Namespace.class),
            any(InputStream.class),
            anyString());

        when(mockedManager.containsFunction(eq(tenant), eq(namespace), eq(sink))).thenReturn(false);

        Response response = registerDefaultSink();
        assertEquals(Status.INTERNAL_SERVER_ERROR.getStatusCode(), response.getStatus());
        assertEquals(new ErrorData("upload failure").reason, ((ErrorData) response.getEntity()).reason);
    }

    @Test
    public void testRegisterSinkSuccess() throws Exception {
        mockStatic(Utils.class);
        doNothing().when(Utils.class);
        Utils.uploadToBookeeper(
            any(Namespace.class),
            any(InputStream.class),
            anyString());

        when(mockedManager.containsFunction(eq(tenant), eq(namespace), eq(sink))).thenReturn(false);

        RequestResult rr = new RequestResult()
            .setSuccess(true)
            .setMessage("source registered");
        CompletableFuture<RequestResult> requestResult = CompletableFuture.completedFuture(rr);
        when(mockedManager.updateFunction(any(FunctionMetaData.class))).thenReturn(requestResult);

        Response response = registerDefaultSink();
        assertEquals(Status.OK.getStatusCode(), response.getStatus());
    }

    @Test
    public void testRegisterSinkFailure() throws Exception {
        mockStatic(Utils.class);
        doNothing().when(Utils.class);
        Utils.uploadToBookeeper(
            any(Namespace.class),
            any(InputStream.class),
            anyString());

        when(mockedManager.containsFunction(eq(tenant), eq(namespace), eq(sink))).thenReturn(false);

        RequestResult rr = new RequestResult()
            .setSuccess(false)
            .setMessage("source failed to register");
        CompletableFuture<RequestResult> requestResult = CompletableFuture.completedFuture(rr);
        when(mockedManager.updateFunction(any(FunctionMetaData.class))).thenReturn(requestResult);

        Response response = registerDefaultSink();
        assertEquals(Status.BAD_REQUEST.getStatusCode(), response.getStatus());
        assertEquals(new ErrorData(rr.getMessage()).reason, ((ErrorData) response.getEntity()).reason);
    }

    @Test
    public void testRegisterSinkInterrupted() throws Exception {
        mockStatic(Utils.class);
        doNothing().when(Utils.class);
        Utils.uploadToBookeeper(
            any(Namespace.class),
            any(InputStream.class),
            anyString());

        when(mockedManager.containsFunction(eq(tenant), eq(namespace), eq(sink))).thenReturn(false);

        CompletableFuture<RequestResult> requestResult = FutureUtil.failedFuture(
            new IOException("Function registeration interrupted"));
        when(mockedManager.updateFunction(any(FunctionMetaData.class))).thenReturn(requestResult);

        Response response = registerDefaultSink();
        assertEquals(Status.INTERNAL_SERVER_ERROR.getStatusCode(), response.getStatus());
        assertEquals(new ErrorData("Function registeration interrupted").reason, ((ErrorData) response.getEntity()).reason);
    }

    //
    // Update Functions
    //

    @Test
    public void testUpdateSinkMissingTenant() throws IOException {
        testUpdateSinkMissingArguments(
            null,
            namespace,
                sink,
            mockedInputStream,
            mockedFormData,
            topicsToSerDeClassName,
            className,
            parallelism,
                "Tenant is not provided");
    }

    @Test
    public void testUpdateSinkMissingNamespace() throws IOException {
        testUpdateSinkMissingArguments(
            tenant,
            null,
                sink,
            mockedInputStream,
            mockedFormData,
            topicsToSerDeClassName,
            className,
            parallelism,
                "Namespace is not provided");
    }

    @Test
    public void testUpdateSinkMissingFunctionName() throws IOException {
        testUpdateSinkMissingArguments(
            tenant,
            namespace,
            null,
            mockedInputStream,
            mockedFormData,
            topicsToSerDeClassName,
            className,
            parallelism,
                "Function Name is not provided");
    }

    @Test
    public void testUpdateSinkMissingPackage() throws IOException {
        testUpdateSinkMissingArguments(
            tenant,
            namespace,
                sink,
            null,
            mockedFormData,
            topicsToSerDeClassName,
            className,
            parallelism,
                "Function Package is not provided");
    }

    @Test
    public void testUpdateSinkMissingPackageDetails() throws IOException {
        testUpdateSinkMissingArguments(
            tenant,
            namespace,
                sink,
            mockedInputStream,
            null,
            topicsToSerDeClassName,
            className,
            parallelism,
                "Function Package is not provided");
    }

    private void testUpdateSinkMissingArguments(
            String tenant,
            String namespace,
            String sink,
            InputStream inputStream,
            FormDataContentDisposition details,
            Map<String, String> inputTopicsMap,
            String className,
            Integer parallelism,
            String expectedError) throws IOException {
        when(mockedManager.containsFunction(eq(tenant), eq(namespace), eq(sink))).thenReturn(true);

        SinkConfig sinkConfig = new SinkConfig();
        if (tenant != null) {
            sinkConfig.setTenant(tenant);
        }
        if (namespace != null) {
            sinkConfig.setNamespace(namespace);
        }
        if (sink != null) {
            sinkConfig.setName(sink);
        }
        if (inputTopicsMap != null) {
            sinkConfig.setTopicToSerdeClassName(inputTopicsMap);
        }
        if (className != null) {
            sinkConfig.setClassName(className);
        }
        if (parallelism != null) {
            sinkConfig.setParallelism(parallelism);
        }

        Response response = resource.updateFunction(
            tenant,
            namespace,
            sink,
            inputStream,
            details,
            null,
            null,
            null,
            null,
            new Gson().toJson(sinkConfig),
                null);

        assertEquals(Status.BAD_REQUEST.getStatusCode(), response.getStatus());
        Assert.assertEquals(((ErrorData) response.getEntity()).reason, new ErrorData(expectedError).reason);
    }

    private Response updateDefaultSink() throws IOException {
        SinkConfig sinkConfig = new SinkConfig();
        sinkConfig.setTenant(tenant);
        sinkConfig.setNamespace(namespace);
        sinkConfig.setName(sink);
        sinkConfig.setClassName(className);
        sinkConfig.setParallelism(parallelism);
        sinkConfig.setTopicToSerdeClassName(topicsToSerDeClassName);

        return resource.updateFunction(
            tenant,
            namespace,
                sink,
            mockedInputStream,
            mockedFormData,
            null,
            null,
            null,
            null,
            new Gson().toJson(sinkConfig),
                null);
    }

    @Test
    public void testUpdateNotExistedSink() throws IOException {
        when(mockedManager.containsFunction(eq(tenant), eq(namespace), eq(sink))).thenReturn(false);

        Response response = updateDefaultSink();
        assertEquals(Status.BAD_REQUEST.getStatusCode(), response.getStatus());
        assertEquals(new ErrorData("Function " + sink + " doesn't exist").reason, ((ErrorData) response.getEntity()).reason);
    }

    @Test
    public void testUpdateSinkUploadFailure() throws Exception {
        mockStatic(Utils.class);
        doThrow(new IOException("upload failure")).when(Utils.class);
        Utils.uploadToBookeeper(
            any(Namespace.class),
            any(InputStream.class),
            anyString());

        when(mockedManager.containsFunction(eq(tenant), eq(namespace), eq(sink))).thenReturn(true);

        Response response = updateDefaultSink();
        assertEquals(Status.INTERNAL_SERVER_ERROR.getStatusCode(), response.getStatus());
        assertEquals(new ErrorData("upload failure").reason, ((ErrorData) response.getEntity()).reason);
    }

    @Test
    public void testUpdateSinkSuccess() throws Exception {
        mockStatic(Utils.class);
        doNothing().when(Utils.class);
        Utils.uploadToBookeeper(
            any(Namespace.class),
            any(InputStream.class),
            anyString());

        when(mockedManager.containsFunction(eq(tenant), eq(namespace), eq(sink))).thenReturn(true);

        RequestResult rr = new RequestResult()
            .setSuccess(true)
            .setMessage("source registered");
        CompletableFuture<RequestResult> requestResult = CompletableFuture.completedFuture(rr);
        when(mockedManager.updateFunction(any(FunctionMetaData.class))).thenReturn(requestResult);

        Response response = updateDefaultSink();
        assertEquals(Status.OK.getStatusCode(), response.getStatus());
    }

    @Test
    public void testUpdateSinkWithUrl() throws IOException {
        Configurator.setRootLevel(Level.DEBUG);

        String fileLocation = FutureUtil.class.getProtectionDomain().getCodeSource().getLocation().getPath();
        String filePackageUrl = "file://" + fileLocation;

        SinkConfig sinkConfig = new SinkConfig();
        sinkConfig.setTopicToSerdeClassName(topicsToSerDeClassName);
        sinkConfig.setTenant(tenant);
        sinkConfig.setNamespace(namespace);
        sinkConfig.setName(sink);
        sinkConfig.setClassName(className);
        sinkConfig.setParallelism(parallelism);

        when(mockedManager.containsFunction(eq(tenant), eq(namespace), eq(sink))).thenReturn(true);
        RequestResult rr = new RequestResult()
                .setSuccess(true)
                .setMessage("source registered");
            CompletableFuture<RequestResult> requestResult = CompletableFuture.completedFuture(rr);
            when(mockedManager.updateFunction(any(FunctionMetaData.class))).thenReturn(requestResult);

        Response response = resource.updateFunction(
            tenant,
            namespace,
                sink,
            null,
            null,
            filePackageUrl,
            null,
            null,
            null,
            new Gson().toJson(sinkConfig),
                null);

        assertEquals(Status.OK.getStatusCode(), response.getStatus());
    }

    @Test
    public void testUpdateSinkFailure() throws Exception {
        mockStatic(Utils.class);
        doNothing().when(Utils.class);
        Utils.uploadToBookeeper(
            any(Namespace.class),
            any(InputStream.class),
            anyString());

        when(mockedManager.containsFunction(eq(tenant), eq(namespace), eq(sink))).thenReturn(true);

        RequestResult rr = new RequestResult()
            .setSuccess(false)
            .setMessage("source failed to register");
        CompletableFuture<RequestResult> requestResult = CompletableFuture.completedFuture(rr);
        when(mockedManager.updateFunction(any(FunctionMetaData.class))).thenReturn(requestResult);

        Response response = updateDefaultSink();
        assertEquals(Status.BAD_REQUEST.getStatusCode(), response.getStatus());
        assertEquals(new ErrorData(rr.getMessage()).reason, ((ErrorData) response.getEntity()).reason);
    }

    @Test
    public void testUpdateSinkInterrupted() throws Exception {
        mockStatic(Utils.class);
        doNothing().when(Utils.class);
        Utils.uploadToBookeeper(
            any(Namespace.class),
            any(InputStream.class),
            anyString());

        when(mockedManager.containsFunction(eq(tenant), eq(namespace), eq(sink))).thenReturn(true);

        CompletableFuture<RequestResult> requestResult = FutureUtil.failedFuture(
            new IOException("Function registeration interrupted"));
        when(mockedManager.updateFunction(any(FunctionMetaData.class))).thenReturn(requestResult);

        Response response = updateDefaultSink();
        assertEquals(Status.INTERNAL_SERVER_ERROR.getStatusCode(), response.getStatus());
        assertEquals(new ErrorData("Function registeration interrupted").reason, ((ErrorData) response.getEntity()).reason);
    }

    //
    // deregister source
    //

    @Test
    public void testDeregisterSinkMissingTenant() throws Exception {
        testDeregisterSinkMissingArguments(
            null,
            namespace,
                sink,
            "Tenant");
    }

    @Test
    public void testDeregisterSinkMissingNamespace() throws Exception {
        testDeregisterSinkMissingArguments(
            tenant,
            null,
                sink,
            "Namespace");
    }

    @Test
    public void testDeregisterSinkMissingFunctionName() throws Exception {
        testDeregisterSinkMissingArguments(
            tenant,
            namespace,
            null,
            "Function Name");
    }

    private void testDeregisterSinkMissingArguments(
        String tenant,
        String namespace,
        String sink,
        String missingFieldName
    ) {
        Response response = resource.deregisterFunction(
            tenant,
            namespace,
            sink,
            null);

        assertEquals(Status.BAD_REQUEST.getStatusCode(), response.getStatus());
        assertEquals(new ErrorData(missingFieldName + " is not provided").reason, ((ErrorData) response.getEntity()).reason);
    }

    private Response deregisterDefaultSink() {
        return resource.deregisterFunction(
            tenant,
            namespace,
                sink,
            null);
    }

    @Test
    public void testDeregisterNotExistedSink() {
        when(mockedManager.containsFunction(eq(tenant), eq(namespace), eq(sink))).thenReturn(false);

        Response response = deregisterDefaultSink();
        assertEquals(Status.NOT_FOUND.getStatusCode(), response.getStatus());
        assertEquals(new ErrorData("Function " + sink + " doesn't exist").reason, ((ErrorData) response.getEntity()).reason);
    }

    @Test
    public void testDeregisterSinkSuccess() throws Exception {
        when(mockedManager.containsFunction(eq(tenant), eq(namespace), eq(sink))).thenReturn(true);

        RequestResult rr = new RequestResult()
            .setSuccess(true)
            .setMessage("source deregistered");
        CompletableFuture<RequestResult> requestResult = CompletableFuture.completedFuture(rr);
        when(mockedManager.deregisterFunction(eq(tenant), eq(namespace), eq(sink))).thenReturn(requestResult);

        Response response = deregisterDefaultSink();
        assertEquals(Status.OK.getStatusCode(), response.getStatus());
        assertEquals(rr.toJson(), response.getEntity());
    }

    @Test
    public void testDeregisterSinkFailure() throws Exception {
        when(mockedManager.containsFunction(eq(tenant), eq(namespace), eq(sink))).thenReturn(true);

        RequestResult rr = new RequestResult()
            .setSuccess(false)
            .setMessage("source failed to deregister");
        CompletableFuture<RequestResult> requestResult = CompletableFuture.completedFuture(rr);
        when(mockedManager.deregisterFunction(eq(tenant), eq(namespace), eq(sink))).thenReturn(requestResult);

        Response response = deregisterDefaultSink();
        assertEquals(Status.BAD_REQUEST.getStatusCode(), response.getStatus());
        assertEquals(new ErrorData(rr.getMessage()).reason, ((ErrorData) response.getEntity()).reason);
    }

    @Test
    public void testDeregisterSinkInterrupted() throws Exception {
        when(mockedManager.containsFunction(eq(tenant), eq(namespace), eq(sink))).thenReturn(true);

        CompletableFuture<RequestResult> requestResult = FutureUtil.failedFuture(
            new IOException("Function deregisteration interrupted"));
        when(mockedManager.deregisterFunction(eq(tenant), eq(namespace), eq(sink))).thenReturn(requestResult);

        Response response = deregisterDefaultSink();
        assertEquals(Status.INTERNAL_SERVER_ERROR.getStatusCode(), response.getStatus());
        assertEquals(new ErrorData("Function deregisteration interrupted").reason, ((ErrorData) response.getEntity()).reason);
    }

    // Source Info doesn't exist. Maybe one day they might be added
    //
    // Get Function Info
    //

    /*
    @Test
    public void testGetFunctionMissingTenant() throws Exception {
        testGetFunctionMissingArguments(
            null,
            namespace,
                source,
            "Tenant");
    }

    @Test
    public void testGetFunctionMissingNamespace() throws Exception {
        testGetFunctionMissingArguments(
            tenant,
            null,
                source,
            "Namespace");
    }

    @Test
    public void testGetFunctionMissingFunctionName() throws Exception {
        testGetFunctionMissingArguments(
            tenant,
            namespace,
            null,
            "Function Name");
    }

    private void testGetFunctionMissingArguments(
        String tenant,
        String namespace,
        String function,
        String missingFieldName
    ) throws IOException {
        Response response = resource.getFunctionInfo(
            tenant,
            namespace,
            function);

        assertEquals(Status.BAD_REQUEST.getStatusCode(), response.getStatus());
        assertEquals(new ErrorData(missingFieldName + " is not provided").reason, ((ErrorData) response.getEntity()).reason);
    }

    private Response getDefaultFunctionInfo() throws IOException {
        return resource.getFunctionInfo(
            tenant,
            namespace,
                source);
    }

    @Test
    public void testGetNotExistedFunction() throws IOException {
        when(mockedManager.containsFunction(eq(tenant), eq(namespace), eq(source))).thenReturn(false);

        Response response = getDefaultFunctionInfo();
        assertEquals(Status.NOT_FOUND.getStatusCode(), response.getStatus());
        assertEquals(new ErrorData("Function " + source + " doesn't exist").reason, ((ErrorData) response.getEntity()).reason);
    }

    @Test
    public void testGetFunctionSuccess() throws Exception {
        when(mockedManager.containsFunction(eq(tenant), eq(namespace), eq(source))).thenReturn(true);

        SinkSpec sinkSpec = SinkSpec.newBuilder()
                .setTopic(outputTopic)
                .setSerDeClassName(outputSerdeClassName).build();
        FunctionDetails functionDetails = FunctionDetails.newBuilder()
                .setClassName(className)
                .setSink(sinkSpec)
                .setName(source)
                .setNamespace(namespace)
                .setProcessingGuarantees(ProcessingGuarantees.ATMOST_ONCE)
                .setTenant(tenant)
                .setParallelism(parallelism)
                .setSource(SourceSpec.newBuilder().setSubscriptionType(subscriptionType)
                        .putAllTopicsToSerDeClassName(topicsToSerDeClassName)).build();
        FunctionMetaData metaData = FunctionMetaData.newBuilder()
            .setCreateTime(System.currentTimeMillis())
            .setFunctionDetails(functionDetails)
            .setPackageLocation(PackageLocationMetaData.newBuilder().setPackagePath("/path/to/package"))
            .setVersion(1234)
            .build();
        when(mockedManager.getFunctionMetaData(eq(tenant), eq(namespace), eq(source))).thenReturn(metaData);

        Response response = getDefaultFunctionInfo();
        assertEquals(Status.OK.getStatusCode(), response.getStatus());
        assertEquals(
            org.apache.pulsar.functions.utils.Utils.printJson(functionDetails),
            response.getEntity());
    }

    //
    // List Functions
    //

    @Test
    public void testListFunctionsMissingTenant() throws Exception {
        testListFunctionsMissingArguments(
            null,
            namespace,
            "Tenant");
    }

    @Test
    public void testListFunctionsMissingNamespace() throws Exception {
        testListFunctionsMissingArguments(
            tenant,
            null,
            "Namespace");
    }

    private void testListFunctionsMissingArguments(
        String tenant,
        String namespace,
        String missingFieldName
    ) {
        Response response = resource.listFunctions(
            tenant,
            namespace);

        assertEquals(Status.BAD_REQUEST.getStatusCode(), response.getStatus());
        assertEquals(new ErrorData(missingFieldName + " is not provided").reason, ((ErrorData) response.getEntity()).reason);
    }

    private Response listDefaultFunctions() {
        return resource.listFunctions(
            tenant,
            namespace);
    }

    @Test
    public void testListFunctionsSuccess() throws Exception {
        List<String> functions = Lists.newArrayList("test-1", "test-2");
        when(mockedManager.listFunctions(eq(tenant), eq(namespace))).thenReturn(functions);

        Response response = listDefaultFunctions();
        assertEquals(Status.OK.getStatusCode(), response.getStatus());
        assertEquals(new Gson().toJson(functions), response.getEntity());
    }
    */
}
