# SK Binge Galaxy — Private Theater Booking Platform

A production-grade, cloud-deployable microservices application for booking private theater experiences.

## Architecture Overview

```
┌──────────┐     ┌──────────────┐     ┌────────────────┐
│ React    │────▶│  API Gateway │────▶│  Auth Service  │ (8081)
│ Frontend │     │  (8080)      │     └────────────────┘
│ (3000)   │     │              │────▶│ Availability   │ (8082)
└──────────┘     │              │     │ Service        │
                 │              │     └────────────────┘
                 │              │────▶│ Booking Service│ (8083)
                 │              │     └────────────────┘
                 │              │────▶│ Payment Service│ (8084)
                 │              │     └────────────────┘
                 └──────────────┘     ┌────────────────┐
                                      │ Notification   │ (8085)
                                      │ Service        │
  ┌────────────────┐                  └────────────────┘
  │ Discovery Srvr │ (8761 - Eureka)
  │ Config Server  │ (8888)
  └────────────────┘
```

## Tech Stack

| Layer | Technology |
|-------|-----------|
| Backend | Java 17, Spring Boot 3.2.5, Spring Cloud 2023.0.1 |
| Frontend | React 18, Vite, React Router 6, Axios |
| Service Discovery | Spring Cloud Netflix Eureka |
| Config | Spring Cloud Config Server (native) |
| API Gateway | Spring Cloud Gateway + JWT Filter |
| Auth | JWT (jjwt 0.12.5), BCrypt, Spring Security |
| Database | PostgreSQL 16 (per-service DBs), MongoDB 7 (notifications) |
| Messaging | Apache Kafka |
| Containerization | Docker, Docker Compose |
| Orchestration | Kubernetes |
| CI/CD | Jenkins |

## Microservices

| Service | Port | Database | Description |
|---------|------|----------|-------------|
| discovery-server | 8761 | — | Eureka service registry |
| config-server | 8888 | — | Centralized configuration |
| api-gateway | 8080 | — | Routing, JWT validation, CORS |
| auth-service | 8081 | PostgreSQL (auth_db) | Registration, login, password reset |
| availability-service | 8082 | PostgreSQL (availability_db) | Date/slot availability, blocking |
| booking-service | 8083 | PostgreSQL (booking_db) | Booking CRUD, event types, add-ons |
| payment-service | 8084 | PostgreSQL (payment_db) | Payment processing, refunds |
| notification-service | 8085 | MongoDB | Email/SMS/WhatsApp notifications |

## Prerequisites

- **Java 17+** and **Maven 3.9+**
- **Node.js 20+** and **npm**
- **Docker** and **Docker Compose**
- **PostgreSQL 16** and **MongoDB 7** (or use Docker Compose)
- **Apache Kafka** (or use Docker Compose)

## Quick Start with Docker Compose

```bash
# 1. Build all backend services
cd backend
mvn clean package -DskipTests

# 2. Build frontend
cd ../frontend
npm install
npm run build

# 3. Start everything
cd ..
docker-compose up --build -d
```

The application will be available at:
- **Frontend**: http://localhost:3000
- **API Gateway**: http://localhost:8080
- **Eureka Dashboard**: http://localhost:8761 (admin/admin)

## Local Development (Without Docker)

### 1. Start Infrastructure
```bash
# Start PostgreSQL, MongoDB, and Kafka locally
# Or use docker-compose for just infrastructure:
docker-compose up postgres mongodb zookeeper kafka -d
```

### 2. Start Backend Services (in order)

```bash
# Terminal 1 - Discovery Server
cd backend/discovery-server
mvn spring-boot:run

# Terminal 2 - Config Server (wait for Discovery to be UP)
cd backend/config-server
mvn spring-boot:run

# Terminal 3 - API Gateway (wait for Config to be UP)
cd backend/api-gateway
mvn spring-boot:run

# Terminal 4-8 - Application services (wait for Config to be UP)
cd backend/auth-service && mvn spring-boot:run
cd backend/availability-service && mvn spring-boot:run
cd backend/booking-service && mvn spring-boot:run
cd backend/payment-service && mvn spring-boot:run
cd backend/notification-service && mvn spring-boot:run
```

### 3. Start Frontend

```bash
cd frontend
npm install
npm run dev
```

Frontend runs at http://localhost:3000 with Vite proxy to the API Gateway.

## Default Credentials

| Role | Email | Password |
|------|-------|----------|
| Admin | admin@skbingegalaxy.com | Admin@123 |

The admin account is auto-seeded on first startup.

## Core Features

### Customer
- Register/Login with JWT authentication
- Browse available dates and time slots
- Book private theater (7 event types, 14+ add-ons)
- Real-time pricing calculation
- Payment initiation with UPI/Card/Bank/Wallet
- View current and past bookings
- Receive email notifications for booking and payment events
- Password reset via OTP

### Admin
- Separate admin login
- Dashboard with booking stats and revenue
- Search, filter, and manage all bookings
- Check-in customers
- Cancel bookings with reason
- Block/unblock dates and time slots
- Initiate refunds
- Retry failed notifications

## Kafka Event Topics

| Topic | Publisher | Consumer |
|-------|-----------|----------|
| booking.created | booking-service | notification-service |
| booking.confirmed | booking-service | notification-service |
| booking.cancelled | booking-service | notification-service |
| payment.success | payment-service | booking-service, notification-service |
| payment.failed | payment-service | booking-service, notification-service |
| notification.send | auth-service | notification-service |
| user.registered | auth-service | notification-service |
| password.reset | auth-service | notification-service |

## API Endpoints

### Auth (`/api/auth`)
- `POST /register` — Register customer
- `POST /login` — Customer login
- `POST /admin/login` — Admin login
- `GET /profile` — Get user profile
- `POST /forgot-password` — Send reset OTP
- `POST /verify-otp` — Verify OTP and reset password

### Availability (`/api/availability`)
- `GET /dates?from=&to=` — Get date availability
- `GET /slots?date=` — Get slot availability
- `POST /admin/block-date` — Block a date
- `POST /admin/block-slot` — Block a slot
- `DELETE /admin/unblock-date` — Unblock a date
- `DELETE /admin/unblock-slot` — Unblock a slot

### Bookings (`/api/bookings`)
- `POST /` — Create booking
- `GET /{ref}` — Get booking by reference
- `GET /my` — Get my bookings
- `GET /my/current` — Current bookings
- `GET /my/past` — Past bookings
- `GET /event-types` — List event types
- `GET /add-ons` — List add-ons
- `GET /admin` — All bookings (paginated)
- `GET /admin/search?keyword=` — Search bookings
- `PATCH /admin/{ref}` — Update booking
- `POST /admin/{ref}/cancel` — Cancel booking
- `POST /admin/{ref}/check-in` — Check-in
- `GET /admin/dashboard-stats` — Dashboard stats

### Payments (`/api/payments`)
- `POST /initiate` — Initiate payment
- `POST /callback` — Payment gateway callback
- `POST /simulate/{txnId}` — Simulate payment (dev)
- `GET /transaction/{txnId}` — Get by transaction ID
- `GET /booking/{ref}` — Payments for a booking
- `GET /my` — My payments
- `POST /admin/refund` — Initiate refund

## Kubernetes Deployment

```bash
# Apply all manifests
kubectl apply -f k8s/namespace.yml
kubectl apply -f k8s/postgres.yml
kubectl apply -f k8s/mongodb.yml
kubectl apply -f k8s/kafka.yml
kubectl apply -f k8s/infrastructure.yml
kubectl apply -f k8s/services.yml
kubectl apply -f k8s/frontend.yml
```

## Project Structure

```
sk-binge-galaxy/
├── backend/
│   ├── pom.xml                    # Parent POM
│   ├── common-lib/                # Shared library (enums, DTOs, events, exceptions)
│   ├── discovery-server/          # Eureka Server
│   ├── config-server/             # Spring Cloud Config (native)
│   │   └── src/.../configurations/ # Per-service YAML configs
│   ├── api-gateway/               # Spring Cloud Gateway + JWT Filter
│   ├── auth-service/              # Authentication & authorization
│   ├── availability-service/      # Date/slot management
│   ├── booking-service/           # Booking management
│   ├── payment-service/           # Payment processing
│   └── notification-service/      # Email/SMS/WhatsApp notifications
├── frontend/                      # React 18 + Vite
│   ├── src/
│   │   ├── components/            # Shared components
│   │   ├── context/               # Auth context
│   │   ├── pages/                 # All pages
│   │   └── services/              # API client + endpoints
│   ├── Dockerfile
│   └── nginx.conf
├── infra/
│   └── init-databases.sql         # PostgreSQL init script
├── k8s/                           # Kubernetes manifests
├── docker-compose.yml
├── Jenkinsfile                    # CI/CD pipeline
└── README.md
```

## License

Private project — SK Binge Galaxy.
