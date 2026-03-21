# EKS Deployment Reference Guide

A step-by-step reference for deploying a Java/Javalin application to AWS EKS using GitHub Actions, based on lessons learned.

---

## Stack

| Tool | Version |
|------|---------|
| JDK | 21 (OpenLogic) |
| Maven | 3.9.13 |
| Javalin | 7.1.0 |
| Docker | Latest |
| AWS CLI | v2 |
| kubectl | Latest compatible with EKS version |
| eksctl | Latest |

---

## 1. Local Environment Setup

### PowerShell (run once to allow local scripts)
```powershell
Set-ExecutionPolicy -ExecutionPolicy RemoteSigned -Scope CurrentUser
```

### Set Java and Maven for current session (dot-source required)
```powershell
. .\setenv.ps1
```
> Do NOT use `setenv.bat` from PowerShell — `.bat` files run in a child process and environment changes do not propagate back to PowerShell.

### Build and run locally
```powershell
mvn clean package
java -jar target/hello-javalin-1.0.0.jar
# Server starts on port 7100
```

---

## 2. Javalin 7 Routing API

In Javalin 7, routes are **not** registered on the `Javalin` instance directly.  
They must be defined via `config.routes` inside `Javalin.create()`:

```java
Javalin.create(config -> {
    config.routes.get("/",       ctx -> ctx.result("Hello, World!"));
    config.routes.get("/health", ctx -> ctx.result("OK"));
}).start(7100);
```

> `config.router` is for path-matching settings (case sensitivity, trailing slashes).  
> `config.routes` is where HTTP routes are registered.

---

## 3. AWS Setup (one-time)

### Create ECR repository
```bash
aws ecr create-repository \
  --repository-name hello-javalin \
  --region us-east-1
```

### Create IAM user for GitHub Actions
1. Create IAM user (e.g. `git-actions-user`)
2. When asked for use case, choose **Command Line Interface (CLI)**
3. Attach these permissions:
   - `AmazonEC2ContainerRegistryFullAccess`
   - Custom inline policy for EKS:
     ```json
     {
       "Version": "2012-10-17",
       "Statement": [{
         "Effect": "Allow",
         "Action": "eks:DescribeCluster",
         "Resource": "*"
       }]
     }
     ```
4. Generate and save the **Access Key ID** and **Secret Access Key**

---

## 4. EKS Cluster Setup (one-time)

### Grant the CI/CD IAM user access to the cluster

#### Step 1 — Check the cluster authentication mode
```bash
aws eks describe-cluster \
  --name <cluster-name> \
  --region us-east-1 \
  --query "cluster.accessConfig"
```

- If result is `CONFIG_MAP` → use `eksctl create iamidentitymapping` (Step 2a)
- If result is `API` or `API_AND_CONFIG_MAP` → use EKS Access Entries (Step 2b)

> **Lesson learned:** Newer EKS clusters (especially EKS Auto Mode) default to `API` mode,  
> which completely ignores the `aws-auth` ConfigMap. Editing the ConfigMap will have no effect.

#### Step 2a — CONFIG_MAP mode (run from the machine that created the cluster)
```bash
eksctl create iamidentitymapping \
  --cluster  <cluster-name> \
  --region   us-east-1 \
  --arn      arn:aws:iam::<account-id>:user/git-actions-user \
  --group    system:masters \
  --username ci-user
```
> Must be run from a machine with existing admin access to the cluster (i.e. the machine used to create it).  
> The `--username ci-user` label is defined here — it does not need to exist anywhere beforehand.

#### Step 2b — API mode (EKS Access Entries)
```bash
aws eks create-access-entry \
  --cluster-name <cluster-name> \
  --region us-east-1 \
  --principal-arn arn:aws:iam::<account-id>:user/git-actions-user

aws eks associate-access-policy \
  --cluster-name <cluster-name> \
  --region us-east-1 \
  --principal-arn arn:aws:iam::<account-id>:user/git-actions-user \
  --policy-arn arn:aws:eks::aws:cluster-access-policy/AmazonEKSClusterAdminPolicy \
  --access-scope type=cluster
```

### Allow nodes to pull from ECR (attach to node group IAM role)
```bash
# Get the node role ARN
aws eks describe-nodegroup \
  --cluster-name <cluster-name> \
  --nodegroup-name <nodegroup-name> \
  --query "nodegroup.nodeRole" --output text

# Attach ECR read access
aws iam attach-role-policy \
  --role-name <node-role-name> \
  --policy-arn arn:aws:iam::aws:policy/AmazonEC2ContainerRegistryReadOnly
```
> With this in place, `imagePullSecrets` in `deployment.yaml` is not needed.

---

## 5. GitHub Actions Secrets

In your GitHub repository: **Settings → Secrets and variables → Actions**

| Secret | Value |
|--------|-------|
| `AWS_ACCESS_KEY_ID` | Access key of `git-actions-user` |
| `AWS_SECRET_ACCESS_KEY` | Secret key of `git-actions-user` |

---

## 6. GitHub Actions Workflow Notes

- Edit the `env:` block at the top of `.github/workflows/deploy.yml`:
  ```yaml
  AWS_REGION:       us-east-1
  ECR_REPOSITORY:   hello-javalin
  EKS_CLUSTER_NAME: <your-cluster-name>
  ```

- Rollout timeout is set to **600s** — necessary for EKS Auto Mode which provisions  
  nodes on demand. First deploy can take 3-5 minutes for node provisioning.  
  Subsequent deploys (nodes already running) complete in under a minute.

- To verify the IAM identity being used during the action, check the  
  **"Update kubeconfig for EKS"** step — it runs `aws sts get-caller-identity`  
  and prints the ARN. This must match the ARN registered in Step 4.

---

## 7. Debugging Checklist

| Symptom | Likely cause | Fix |
|---------|-------------|-----|
| `mvn` not found in PowerShell after running `setenv.bat` | `.bat` doesn't set env in PowerShell | Use `. .\setenv.ps1` instead |
| `cannot find symbol: method get()` on `Javalin` | Wrong Javalin 7 routing API | Use `config.routes.get()` inside `create()` |
| `cannot find symbol: method mount()` on `RouterConfig` | `config.router` is for settings, not routes | Use `config.routes` |
| `the server has asked for the client to provide credentials` | IAM user not mapped in cluster auth | Check auth mode — use Access Entries if API mode |
| `aws-auth` ConfigMap edits have no effect | Cluster is in `API` auth mode | Create EKS Access Entry instead |
| Rollout timeout in GitHub Actions | EKS Auto Mode provisioning new nodes | Increase `--timeout` to 600s; pods are likely running fine |
| `FailedToRetrieveImagePullSecret: ecr-registry-secret` | Secret doesn't exist but nodes pull via IAM | Remove `imagePullSecrets` from `deployment.yaml` |
