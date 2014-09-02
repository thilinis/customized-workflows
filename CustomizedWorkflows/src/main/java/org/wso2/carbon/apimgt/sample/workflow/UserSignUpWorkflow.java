/*
*  Copyright (c) 2005-2011, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
*
*  WSO2 Inc. licenses this file to you under the Apache License,
*  Version 2.0 (the "License"); you may not use this file except
*  in compliance with the License.
*  You may obtain a copy of the License at
*
*    http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing,
* software distributed under the License is distributed on an
* "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
* KIND, either express or implied.  See the License for the
* specific language governing permissions and limitations
* under the License.
*/

package org.wso2.carbon.apimgt.sample.workflow;

import org.apache.axiom.om.util.AXIOMUtil;
import org.apache.axis2.AxisFault;
import org.apache.axis2.Constants;
import org.apache.axis2.addressing.EndpointReference;
import org.apache.axis2.client.Options;
import org.apache.axis2.client.ServiceClient;
import org.apache.axis2.transport.http.HTTPConstants;
import org.apache.axis2.transport.http.HttpTransportProperties;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.carbon.apimgt.impl.APIConstants;
import org.wso2.carbon.apimgt.impl.APIManagerConfiguration;
import org.wso2.carbon.apimgt.impl.dto.WorkflowDTO;
import org.wso2.carbon.apimgt.impl.internal.ServiceReferenceHolder;
import org.wso2.carbon.apimgt.impl.workflow.UserSignUpWorkflowExecutor;
import org.wso2.carbon.apimgt.impl.workflow.WorkflowConstants;
import org.wso2.carbon.apimgt.impl.workflow.WorkflowException;
import org.wso2.carbon.apimgt.impl.workflow.WorkflowStatus;

import javax.xml.stream.XMLStreamException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class UserSignUpWorkflow extends UserSignUpWorkflowExecutor {

    private static final Log log = LogFactory.getLog(UserSignUpWorkflow.class);

    private String serviceEndpoint;

    private String username;

    private String password;

    private String contentType;

    @Override
    public String getWorkflowType() {
        return WorkflowConstants.WF_TYPE_AM_USER_SIGNUP;
    }

    @Override
    public void execute(WorkflowDTO workflowDTO) throws WorkflowException {

        log.info("Executing User SignUp Webservice Workflow");
        //service URl modifications for tenants
        int tenantID = workflowDTO.getTenantId();
        String tenantDomain = workflowDTO.getTenantDomain();
        String tempServiceEndpoint=serviceEndpoint;

        if (tenantID != -1234) {
            String regex = "https?:[\\/]{2}.+:[0-9]+\\/services\\/";
            Matcher m = Pattern.compile(regex).matcher(serviceEndpoint);
            m.find();
            int index = m.end();
            tempServiceEndpoint=serviceEndpoint.substring(0, index)+"t/" + tenantDomain + "/"+serviceEndpoint.substring(index, serviceEndpoint.length());

        }
        log.info("Service Endpoint:" + tempServiceEndpoint);
        //End of service URl modifications for tenants
        try {
            ServiceClient client = new ServiceClient(ServiceReferenceHolder.getInstance()
                    .getContextService().getClientConfigContext(), null);
            Options options = new Options();
            options.setAction("http://workflow.registeruser.apimgt.carbon.wso2.org/initiate");
            options.setTo(new EndpointReference(tempServiceEndpoint));
            if(contentType != null){
                options.setProperty(Constants.Configuration.MESSAGE_TYPE, contentType);
            }

            HttpTransportProperties.Authenticator auth = new HttpTransportProperties.Authenticator();

            //Consider this as a secured service if username and password are not null. Unsecured if not.
            if(username != null && password != null){
                auth.setUsername(username);
                auth.setPassword(password);
                auth.setPreemptiveAuthentication(true);
                List<String> authSchemes = new ArrayList<String>();
                authSchemes.add(HttpTransportProperties.Authenticator.BASIC);
                auth.setAuthSchemes(authSchemes);

                if(contentType == null){
                    options.setProperty(Constants.Configuration.MESSAGE_TYPE, HTTPConstants.MEDIA_TYPE_APPLICATION_XML);
                }
                options.setProperty(HTTPConstants.AUTHENTICATE, auth);
                options.setManageSession(true);
            }


            client.setOptions(options);

            String payload = "<wor:UserSignupProcessRequest xmlns:wor=\"http://workflow.registeruser.apimgt.carbon.wso2.org\">\n" +
                    "         <wor:userName>$1</wor:userName>\n" +
                    "         <wor:tenantDomain>$2</wor:tenantDomain>\n" +
                    "         <wor:workflowExternalRef>$3</wor:workflowExternalRef>\n" +
                    "         <wor:callBackURL>$4</wor:callBackURL>\n" +
                    "      </wor:UserSignupProcessRequest>";

            String callBackURL = workflowDTO.getCallbackUrl();

            payload = payload.replace("$1", workflowDTO.getWorkflowReference()) ;
            payload = payload.replace("$2", workflowDTO.getTenantDomain());
            payload = payload.replace("$3", workflowDTO.getExternalWorkflowReference());
            payload = payload.replace("$4", callBackURL != null ? callBackURL : "?");

            client.fireAndForget(AXIOMUtil.stringToOM(payload));
            super.execute(workflowDTO);
        } catch (AxisFault axisFault) {
            log.error("Error sending out message", axisFault);
            throw new WorkflowException("Error sending out message", axisFault);
        } catch (XMLStreamException e) {
            log.error("Error converting String to OMElement", e);
            throw new WorkflowException("Error converting String to OMElement", e);
        }
    }

    @Override
    public void complete(WorkflowDTO workflowDTO) throws WorkflowException {
        workflowDTO.setStatus(workflowDTO.getStatus());
        workflowDTO.setUpdatedTime(System.currentTimeMillis());
        log.info("User Sign Up [Complete] Workflow Invoked. Workflow ID : " + workflowDTO.getExternalWorkflowReference() + "Workflow State : "+ workflowDTO.getStatus());

        super.complete(workflowDTO);
        if(WorkflowStatus.APPROVED.equals(workflowDTO.getStatus())){
            APIManagerConfiguration config = ServiceReferenceHolder.getInstance().getAPIManagerConfigurationService().getAPIManagerConfiguration();
            String serverURL = config.getFirstProperty(APIConstants.AUTH_MANAGER_URL);
            String adminUsername = config.getFirstProperty(APIConstants.AUTH_MANAGER_USERNAME);
            String adminPassword = config.getFirstProperty(APIConstants.AUTH_MANAGER_PASSWORD);
            if (serverURL == null || adminUsername == null || adminPassword == null) {
                throw new WorkflowException("Required parameter missing to connect to the" +
                        " authentication manager");
            }

            String role = config.getFirstProperty(APIConstants.SELF_SIGN_UP_ROLE);
            if (role == null) {
                throw new WorkflowException("Subscriber role undefined for self registration");
            }

            try{
                /* update users role list with SELF_SIGN_UP_ROLE role */
                updateRolesOfUser(serverURL, adminUsername, adminPassword, workflowDTO.getWorkflowReference(), role);
            }catch(Exception e){
                throw new WorkflowException("Error while assigning role to user", e);

            }
        }

    }


    @Override
    public List<WorkflowDTO> getWorkflowDetails(String workflowStatus) throws WorkflowException{
        return null;
    }

    public String getContentType() {
        return contentType;
    }

    public void setContentType(String contentType) {
        this.contentType = contentType;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getServiceEndpoint() {
        return serviceEndpoint;
    }

    public void setServiceEndpoint(String serviceEndpoint) {
        this.serviceEndpoint = serviceEndpoint;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }
}
