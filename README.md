# Hello Javalin — AWS EKS Hello World

A minimal **Hello World** REST API built with [Javalin 7.1.0](https://javalin.io/) and Java 21, packaged as a Docker container and automatically deployed to **AWS EKS** via GitHub Actions on every push to `master`.

---

## Table of Contents
1. [Stack & Requirements](#stack--requirements)
2. [Project Structure](#project-structure)
3. [Local Development](#local-development)
4. [Endpoints](#endpoints)
5. [Docker Build (local)](#docker-build-local)
6. [AWS Setup (one-time)](#aws-setup-one-time)
7. [GitHub Secrets](#github-secrets)
8. [CI/CD Pipeline](#cicd-pipeline)
9. [Deploying Manually](#deploying-manually)

---

## Stack & Requirements

| Tool | Version / Location |
|------|--------------------|
| JDK  | 21 — `F:\java\openlogic-openjdk-21.0.10+7-windows-x64` |
| Maven | 3.9.13 — `F:\aws\apache-maven-3.9.13-bin\apache-maven-3.9.13` |
| Javalin | 7.1.0 |
| Docker | Any recent version |
| AWS CLI | v2 |
| kubectl | Compatible with your EKS version |

### Environment variables (Windows — set once per session)
```bat
set JAVA_HOME=F:\java\openlogic-openjdk-21.0.10+7-windows-x64
set PATH=%JAVA_HOME%\bin;F:\aws\apache-maven-3.9.13-bin\apache-maven-3.9.13\bin;%PATH%
```

---

## Project Structure

```
git_aks_hello/
├── .github/
│   └── workflows/
│       └── deploy.yml          # GitHub Actions CI/CD pipeline
├── k8s/
│   ├── deployment.yaml         # Kubernetes Deployment (2 replicas)
│   └── service.yaml            # Kubernetes LoadBalancer Service
├── src/
│   └── main/
│       └── java/
│           └── com/example/
│               └── App.java    # Javalin application entry point
├── Dockerfile                  # Multi-stage Docker build (JDK 21 → JRE 21)
├── pom.xml                     # Maven build — Javalin 7.1.0, fat JAR via Shade plugin
└── README.md
```

---

## Local Development

### 1. Verify toolchain
```bash
java -version        # should print openjdk 21
mvn  -version        # should print Apache Maven 3.9.13
```

### 2. Build
```bash
mvn clean package
```
This produces `target/hello-javalin-1.0.0.jar` — a self-contained fat JAR.

### 3. Run
```bash
java -jar target/hello-javalin-1.0.0.jar
```
The server starts on **port 8080** by default.
Override with the `PORT` environment variable:
```bash
PORT=9090 java -jar target/hello-javalin-1.0.0.jar
```

---

## Endpoints

| Method | Path | Description |
|--------|------|-------------|
| GET | `/` | Returns `Hello, World!` |
| GET | `/health` | Liveness probe — returns `OK` |
| GET | `/info` | App version, Java version, port |

---

## Docker Build (local)

```bash
# Build image
docker build -t hello-javalin:local .

# Run container
docker run -p 8080:8080 hello-javalin:local

# Test
curl http://localhost:8080/
curl http://localhost:8080/health
```

---

## AWS Setup (one-time)

### 1. Create an ECR repository
```bash
aws ecr create-repository \
  --repository-name hello-javalin \
  --region us-east-1
```
Note the URI — it looks like `<account-id>.dkr.ecr.us-east-1.amazonaws.com/hello-javalin`.

### 2. Create an EKS cluster (skip if you already have one)
```bash
eksctl create cluster \
  --name  my-eks-cluster \
  --region us-east-1 \
  --nodes 2 \
  --node-type t3.small
```

### 3. Create an IAM user for CI/CD with the following policies
- `AmazonEC2ContainerRegistryFullAccess`
- `AmazonEKSClusterPolicy`
- A custom inline policy to allow `eks:DescribeCluster` and `eks:UpdateCluster`

Generate **Access Key ID** and **Secret Access Key** for this user.

### 4. Allow the IAM user to access the cluster (run on a machine that already has admin access)
```bash
eksctl create iamidentitymapping \
  --cluster  my-eks-cluster \
  --region   us-east-1 \
  --arn      arn:aws:iam::<account-id>:user/<ci-user> \
  --group    system:masters \
  --username ci-user
```

---

## GitHub Secrets

In your GitHub repository go to **Settings → Secrets and variables → Actions** and add:

| Secret name | Value |
|-------------|-------|
| `AWS_ACCESS_KEY_ID` | Access key of the CI/CD IAM user |
| `AWS_SECRET_ACCESS_KEY` | Secret key of the CI/CD IAM user |

---

## CI/CD Pipeline

File: [.github/workflows/deploy.yml](.github/workflows/deploy.yml)

The pipeline triggers on **every push to `master`** and runs these steps:

1. **Checkout** source code
2. **Set up JDK 21** (Temurin) with Maven dependency cache
3. **`mvn clean package`** — builds the fat JAR
4. **Configure AWS credentials** from GitHub Secrets
5. **Log in to Amazon ECR**
6. **Build & push Docker image** tagged with the Git SHA (and `latest`)
7. **Update kubeconfig** for the EKS cluster
8. **`kubectl apply`** — deploys/updates the Deployment and Service
9. **`kubectl rollout status`** — waits for the rollout to complete (timeout 120 s)

### Customise the pipeline
Edit the `env:` block at the top of `deploy.yml`:
```yaml
env:
  AWS_REGION:       us-east-1         # your AWS region
  ECR_REPOSITORY:   hello-javalin     # your ECR repo name
  EKS_CLUSTER_NAME: my-eks-cluster    # your EKS cluster name
```

---

## Deploying Manually

If you want to deploy from your local machine without pushing to GitHub:

```bash
# 1. Build JAR
mvn clean package

# 2. Authenticate Docker to ECR
aws ecr get-login-password --region us-east-1 | \
  docker login --username AWS --password-stdin \
  <account-id>.dkr.ecr.us-east-1.amazonaws.com

# 3. Build & push image
IMAGE=<account-id>.dkr.ecr.us-east-1.amazonaws.com/hello-javalin:manual
docker build -t $IMAGE .
docker push  $IMAGE

# 4. Update kubeconfig
aws eks update-kubeconfig --region us-east-1 --name my-eks-cluster

# 5. Deploy
sed "s|IMAGE_PLACEHOLDER|$IMAGE|g" k8s/deployment.yaml | kubectl apply -f -
kubectl apply -f k8s/service.yaml
kubectl rollout status deployment/hello-javalin

# 6. Get the public URL
kubectl get svc hello-javalin
# Look for the EXTERNAL-IP column — this is your AWS ELB hostname
```
