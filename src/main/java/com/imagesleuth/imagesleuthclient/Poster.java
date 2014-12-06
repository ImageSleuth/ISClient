/*
 * Copyright 2014 The Friedland Group, Inc.
 *
 * -----------------------------------------------------------------
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 */
package com.imagesleuth.imagesleuthclient;

import com.thefriedlandgroup.XMLTools2.Test;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.imaging.ImageInfo;
import org.apache.commons.imaging.ImageReadException;
import org.apache.commons.imaging.Imaging;
import org.apache.commons.io.IOUtils;
import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.concurrent.FutureCallback;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.mime.MultipartEntityBuilder;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.nio.client.CloseableHttpAsyncClient;
import org.apache.http.impl.nio.client.HttpAsyncClients;
import org.apache.http.message.BasicHeader;
import org.apache.http.nio.entity.NByteArrayEntity;

/**
 *
 * @author Noah Friedland at The Friedland Group, Inc
 */
public class Poster {

    final HashMap<String, File> idHash = new HashMap<>();

    public Poster(final ISAsyncClient hac2) throws IOException, ImageReadException, InterruptedException {
        Test.testNull(hac2);

        final CountDownLatch latch = new CountDownLatch(hac2.iFiles.size());

        ArrayList<Header> harray = new ArrayList<>();
        harray.add(new BasicHeader("Authorization", "Basic " + Base64.encodeBase64String(
                (hac2.user + ":" + hac2.password).getBytes())));
        
        final String url = (!hac2.url.startsWith("http")) ?
                "http://" + hac2.url + "/api/v1/job" :
                hac2.url + "/api/v1/job";
        System.out.println("post url "+url);

        CredentialsProvider credsProvider = new BasicCredentialsProvider();
        UsernamePasswordCredentials credentials
                = new UsernamePasswordCredentials(hac2.user, hac2.password);
        credsProvider.setCredentials(AuthScope.ANY, credentials);
        RequestConfig requestConfig = RequestConfig.custom()
                .setSocketTimeout(10000)
                .setConnectTimeout(3000).build();
        long start = new Date().getTime();

        try (CloseableHttpAsyncClient httpclient = HttpAsyncClients.custom()
                .setDefaultHeaders(harray)
                .setDefaultCredentialsProvider(credsProvider)
                .setDefaultRequestConfig(requestConfig)
                .build()) {
            httpclient.start();
            for (final File img : hac2.iFiles) {
                byte[] imageAsBytes = IOUtils.toByteArray(new FileInputStream(img));
                ImageInfo info = Imaging.getImageInfo(imageAsBytes);
                final HttpPost request = new HttpPost(url);
                String boundary = UUID.randomUUID().toString();
                HttpEntity mpEntity = MultipartEntityBuilder.create()
                        .setBoundary("-------------" + boundary)
                        .addBinaryBody("file", imageAsBytes, ContentType.create(info.getMimeType()), img.getName())
                        .build();
                ByteArrayOutputStream baoStream = new ByteArrayOutputStream();
                mpEntity.writeTo(baoStream);
                request.setHeader("Content-Type", "multipart/form-data;boundary=-------------" + boundary);
                //equest.setHeader("Content-Type", "multipart/form-data");                               
                NByteArrayEntity entity = new NByteArrayEntity(baoStream.toByteArray(), ContentType.MULTIPART_FORM_DATA);
                request.setEntity(entity);
                httpclient.execute(request, new FutureCallback<HttpResponse>() {

                    @Override
                    public void completed(final HttpResponse response) {
                        latch.countDown();
                        int code = response.getStatusLine().getStatusCode();
                        //System.out.println(" response code: " + code + " for image: "+img.getName());                        
                        if (response.getEntity() != null && code == 202) {
                            StringWriter writer = new StringWriter();
                            try {
                                IOUtils.copy(response.getEntity().getContent(), writer);
                                String id = writer.toString();
                                writer.close();
                                //System.out.println(" response id: " + id + " for image "+ img.getName()); 
                                idHash.put(id, img);
                         
                            } catch (Exception ex) {
                                ex.printStackTrace();
                            }
                        } else {
                            System.out.println(" response code: " + code + " for image: "+img.getName() +
                                    " reason " + response.getStatusLine().getReasonPhrase());
                        }
                    }

                    @Override
                    public void failed(final Exception ex) {
                        latch.countDown();
                        System.out.println(request.getRequestLine() + "->" + ex);
                    }

                    @Override
                    public void cancelled() {
                        latch.countDown();
                        System.out.println(request.getRequestLine() + " cancelled");
                    }

                });
            }
            latch.await();
            //System.out.println("Shutting down");
        }
        long end = new Date().getTime();
        System.out.println("Done: posting "+ idHash.size() +" time elapsed: " + (end - start));
        hac2.idHash = idHash;
    }
}
