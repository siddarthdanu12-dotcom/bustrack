import os
from flask import Flask, request, jsonify
from flask_socketio import SocketIO, emit
from flask_jwt_extended import JWTManager, create_access_token, jwt_required, get_jwt_identity
from flask_cors import CORS
from models import db, User, Route, Stop, Bus, DriverLocation
from sqlalchemy import text
from datetime import datetime
from dotenv import load_dotenv

load_dotenv()

app = Flask(__name__)
app.config['SQLALCHEMY_DATABASE_URI'] = os.getenv('DATABASE_URL', 'sqlite:///smart_bus.db')
app.config['SQLALCHEMY_TRACK_MODIFICATIONS'] = False
app.config['JWT_SECRET_KEY'] = os.getenv('JWT_SECRET_KEY', 'smart-bus-secret-key-2024')

CORS(app)
db.init_app(app)
jwt = JWTManager(app)
socketio = SocketIO(app, cors_allowed_origins="*")

with app.app_context():
    db.create_all()

    # --- Migration Logic ---
    columns_to_check = [
        ("home_route_info", "VARCHAR(255)"),
        ("status", "VARCHAR(20) DEFAULT 'INACTIVE'"),
        ("route_type", "VARCHAR(20) DEFAULT 'COLLEGE'"),
        ("time_period", "VARCHAR(20)"),
        ("departure_time", "VARCHAR(50)")
    ]
    with db.engine.connect() as conn:
        for col_name, col_type in columns_to_check:
            try:
                conn.execute(text(f"ALTER TABLE buses ADD COLUMN {col_name} {col_type}"))
                conn.commit()
            except Exception: pass

    # Clean up: Reset status on start (BUT KEEP DRIVER ASSIGNMENTS)
    try:
        # We only reset status to INACTIVE so tracking stops if server crashes,
        # but we don't clear driver_id so they don't have to re-enter bus number.
        Bus.query.update({Bus.status: 'INACTIVE'})
        db.session.commit()
    except Exception: pass

    # Initialize Admin
    admin_email = "graphicera@gmail.com"
    if not User.query.filter_by(email=admin_email).first():
        admin = User(name="Admin", email=admin_email, role="admin")
        admin.set_password("bustransport12")
        db.session.add(admin); db.session.commit()

# --- Auth Endpoints ---

@app.route('/api/auth/register', methods=['POST'])
def register():
    try:
        data = request.json
        if not data or 'email' not in data or 'password' not in data:
            return jsonify({"msg": "Missing registration data"}), 400

        if User.query.filter_by(email=data['email']).first():
            return jsonify({"msg": "User already exists"}), 400

        user = User(name=data.get('name', 'User'), email=data['email'], role=data.get('role', 'student'))
        user.set_password(data['password'])
        db.session.add(user)
        db.session.commit()
        return jsonify({"msg": "User created"}), 201
    except Exception as e:
        print(f"Register Error: {e}")
        return jsonify({"msg": str(e)}), 500

@app.route('/api/auth/login', methods=['POST'])
def login():
    data = request.json
    user = User.query.filter_by(email=data['email']).first()
    if user and user.check_password(data['password']):
        access_token = create_access_token(identity=str(user.id))
        return jsonify(access_token=access_token, role=user.role, user_id=user.id, name=user.name), 200
    return jsonify({"msg": "Bad credentials"}), 401

# --- Driver Endpoints ---

@app.route('/api/driver/assign-bus', methods=['POST'])
@jwt_required()
def driver_assign_bus():
    try:
        driver_id = int(get_jwt_identity())
        data = request.json
        bus_number = str(data.get('bus_number', '')).strip()

        if not bus_number:
            return jsonify({"msg": "Bus number is required"}), 400

        # Check if another driver is using it
        existing_active = Bus.query.filter_by(bus_number=bus_number, status='ACTIVE').first()
        if existing_active and existing_active.driver_id != driver_id:
            return jsonify({"msg": "Bus already assigned to another active driver"}), 400

        # Unassign driver from previous buses
        previous_buses = Bus.query.filter_by(driver_id=driver_id).all()
        for prev_bus in previous_buses:
            prev_bus.status = 'INACTIVE'
            prev_bus.driver_id = None

        bus = Bus.query.filter_by(bus_number=bus_number).first()
        if bus:
            bus.driver_id = driver_id
            # Note: We don't automatically set it to ACTIVE here,
            # driver will do it when they tap "Start Tracking" in app
            # BUT for current flow compatibility, let's keep it as is or match app expectation
            bus.status = 'INACTIVE'
        else:
            # CREATE NEW BUS dynamically
            bus = Bus(bus_number=bus_number, driver_id=driver_id, status='INACTIVE', home_route_info="Not Set")
            db.session.add(bus)

        db.session.commit()
        return jsonify({"bus_number": bus.bus_number, "home_route_info": bus.home_route_info or "Not Set", "status": bus.status}), 200
    except Exception as e:
        db.session.rollback()
        print(f"ERROR in assign_bus: {str(e)}")
        return jsonify({"msg": f"Error: {str(e)}"}), 500

@app.route('/api/driver/bus', methods=['GET'])
@jwt_required()
def get_driver_bus():
    driver_id = int(get_jwt_identity())
    bus = Bus.query.filter_by(driver_id=driver_id).first()
    if not bus: return jsonify({"msg": "No bus"}), 404
    return jsonify({"id": bus.id, "bus_number": bus.bus_number, "home_route_info": bus.home_route_info, "status": bus.status}), 200

# --- Admin Endpoints ---

@app.route('/api/admin/buses', methods=['GET'])
@jwt_required()
def get_admin_buses():
    buses = Bus.query.all()
    return jsonify([{"id": b.id, "bus_number": b.bus_number, "home_route_info": b.home_route_info, "status": b.status, "route_type": b.route_type, "time_period": b.time_period, "departure_time": b.departure_time} for b in buses]), 200

@app.route('/api/admin/update-bus-home-info', methods=['POST'])
@jwt_required()
def update_bus_home_info():
    data = request.json
    bus = Bus.query.get(data.get('bus_id'))
    if not bus: return jsonify({"msg": "Bus not found"}), 404

    bus.home_route_info = data.get('home_route_info')
    bus.route_type = data.get('route_type', 'HOME')
    bus.time_period = data.get('time_period')
    bus.departure_time = data.get('departure_time')

    db.session.commit()
    return jsonify({"msg": "Updated"}), 200

# --- Student/Common Endpoints ---

@app.route('/api/buses', methods=['GET'])
def get_all_buses():
    buses = Bus.query.all()
    result = []
    for bus in buses:
        driver_loc = DriverLocation.query.filter_by(driver_id=bus.driver_id).order_by(DriverLocation.timestamp.desc()).first()
        result.append({
            "id": bus.id,
            "bus_number": bus.bus_number,
            "home_route_info": bus.home_route_info,
            "status": bus.status,
            "lat": driver_loc.latitude if driver_loc else None,
            "lng": driver_loc.longitude if driver_loc else None,
            "is_home_bus": bus.route_type == "HOME",
            "time_period": bus.time_period,
            "departure_time": bus.departure_time,
            "driver_name": bus.driver.name if bus.driver else None
        })
    return jsonify(result), 200

# --- Socket.IO ---

@socketio.on('update_location')
def handle_location_update(data):
    driver_id = data.get('driver_id')
    lat = data.get('lat')
    lng = data.get('lng')
    if driver_id and lat and lng:
        # Update bus status to ACTIVE when location is received
        bus = Bus.query.filter_by(driver_id=driver_id).first()
        if bus and bus.status != 'ACTIVE':
            bus.status = 'ACTIVE'
            db.session.commit()

        loc = DriverLocation(driver_id=driver_id, latitude=lat, longitude=lng)
        db.session.add(loc); db.session.commit()
        emit('bus_location_update', {'driver_id': driver_id, 'lat': lat, 'lng': lng}, broadcast=True)


if __name__ == "__main__":
    port = int(os.environ.get("PORT", 5000))
    app.run(host="0.0.0.0", port=port)
