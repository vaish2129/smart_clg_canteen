const state = {
  items: [],
  orders: [],
  category: "All",
  search: "",
  cart: loadCart(),
  user: null,
  loginRole: "STUDENT",
  authPage: "login",
  pendingResetEmail: "",
  lastResetEmail: ""
};

const byId = (id) => document.getElementById(id);
const money = (amount) => new Intl.NumberFormat("en-IN", {
  style: "currency",
  currency: "INR",
  maximumFractionDigits: 2
}).format(Number(amount) || 0);

function escapeHtml(value) {
  return String(value).replace(/[&<>"']/g, (character) => ({
    "&": "&amp;",
    "<": "&lt;",
    ">": "&gt;",
    '"': "&quot;",
    "'": "&#39;"
  })[character]);
}

function loadCart() {
  try {
    const saved = JSON.parse(localStorage.getItem("campusCravingsCart") || "[]");
    return Array.isArray(saved) ? saved.filter((line) =>
      line && typeof line.id === "string" && Number.isInteger(line.quantity) && line.quantity > 0
    ) : [];
  } catch (error) {
    console.error("Could not read the saved cart.", error);
    return [];
  }
}

function saveCart() {
  localStorage.setItem("campusCravingsCart", JSON.stringify(state.cart));
  renderCart();
}

async function api(path, options = {}) {
  const response = await fetch(path, {
    ...options,
    headers: {
      ...(options.body ? { "Content-Type": "application/json" } : {}),
      ...options.headers
    }
  });
  const data = await response.json();
  if (!response.ok) {
    if (response.status === 401 && !path.startsWith("/api/auth/")) {
      showLogin();
    }
    throw new Error(data.error || "Something went wrong. Please try again.");
  }
  return data;
}

function showLogin() {
  state.user = null;
  byId("auth-gate").hidden = false;
  byId("admin-section").hidden = true;
  closeCart();
  document.querySelectorAll("[data-role]").forEach((element) => {
    element.hidden = true;
  });
  byId("account-name").hidden = true;
  byId("sign-out").hidden = true;
  renderMenu();
  renderCart();
}

function setUser(user) {
  state.user = user;
  byId("auth-gate").hidden = true;
  byId("account-name").textContent = user.identity;
  byId("account-name").hidden = false;
  byId("sign-out").hidden = false;
  document.querySelectorAll("[data-role]").forEach((element) => {
    element.hidden = element.dataset.role !== user.role;
  });
  byId("item-admin-card").hidden = user.role !== "ADMIN";
  byId("admin-section").hidden = true;
  byId("admin-section").querySelector(".admin-heading h2").innerHTML =
    user.role === "ADMIN" ? "Admin <span>desk.</span>" : "Staff <span>order desk.</span>";
  byId("admin-section").querySelector(".eyebrow").textContent =
    user.role === "ADMIN" ? "CANTEEN CONTROL" : "ORDER MANAGEMENT";
  byId("admin-section").querySelector(".admin-disclaimer").hidden = user.role !== "ADMIN";
  renderMenu();
  renderCart();
  renderAdminItems();
}

function showAuthPage(page) {
  state.authPage = page;
  const isLogin = page === "login";
  const studentLogin = isLogin && state.loginRole === "STUDENT";
  byId("login-tabs").hidden = !isLogin;
  byId("student-login-form").hidden = !studentLogin;
  byId("staff-login-form").hidden = !isLogin || state.loginRole !== "STAFF";
  byId("admin-login-form").hidden = !isLogin || state.loginRole !== "ADMIN";
  byId("student-login-links").hidden = !studentLogin;
  byId("student-register-form").hidden = page !== "register";
  byId("forgot-password-form").hidden = page !== "forgot";
  byId("reset-password-form").hidden = page !== "reset";
  byId("reset-code-note").hidden = page !== "reset";
  byId("login-error").hidden = true;

  const pageCopy = {
    login: ["Good to have <span>you here.</span>", "Sign in to order your favourites or manage the canteen."],
    register: ["Create your <span>password.</span>", "Set up your student account with your college email."],
    forgot: ["Forgot your <span>password?</span>", "Request a one-time code to reset your student password."],
    reset: ["Choose a <span>new password.</span>", "Enter the 6-digit reset code shown below."]
  }[page];
  byId("auth-title").innerHTML = pageCopy[0];
  byId("auth-intro").textContent = pageCopy[1];
}

function selectLoginRole(role) {
  state.loginRole = role;
  document.querySelectorAll("[data-login-role]").forEach((tab) => {
    const active = tab.dataset.loginRole === role;
    tab.classList.toggle("active", active);
    tab.setAttribute("aria-selected", String(active));
  });
  showAuthPage("login");
}

async function submitLogin(form, role) {
  const formData = new FormData(form);
  const details = Object.fromEntries(formData.entries());
  byId("login-error").hidden = true;
  const button = form.querySelector('button[type="submit"]');
  button.disabled = true;
  try {
    const user = await api("/api/auth/login", {
      method: "POST",
      body: JSON.stringify({ ...details, role })
    });
    setUser(user);
    notify(`Signed in as ${role.toLowerCase()}.`);
    try {
      await loadOrders();
    } catch (error) {
      notify(error.message);
    }
  } catch (error) {
    if (state.user) {
      notify(error.message);
    } else {
      byId("login-error").textContent = error.message;
      byId("login-error").hidden = false;
    }
  } finally {
    button.disabled = false;
  }
}

function showAuthError(message) {
  byId("login-error").textContent = message;
  byId("login-error").hidden = false;
}

async function submitStudentRegistration(form) {
  const details = Object.fromEntries(new FormData(form).entries());
  if (details.password !== details.confirmPassword) {
    showAuthError("The passwords do not match.");
    return;
  }
  const button = form.querySelector('button[type="submit"]');
  byId("login-error").hidden = true;
  button.disabled = true;
  try {
    const user = await api("/api/auth/register", {
      method: "POST",
      body: JSON.stringify({ email: details.email, password: details.password })
    });
    setUser(user);
    notify("Your student account is ready.");
    try {
      await loadOrders();
    } catch (error) {
      notify(error.message);
    }
  } catch (error) {
    showAuthError(error.message);
  } finally {
    button.disabled = false;
  }
}

async function requestStudentPasswordReset(form) {
  const email = new FormData(form).get("email").toString().trim();
  const button = form.querySelector('button[type="submit"]');
  byId("login-error").hidden = true;
  button.disabled = true;
  try {
    const result = await api("/api/auth/forgot-password", {
      method: "POST",
      body: JSON.stringify({ email })
    });
    state.pendingResetEmail = email;
    state.lastResetEmail = email;
    byId("reset-code-note").textContent = result.resetCode
      ? `${result.message} Reset code: ${result.resetCode}`
      : result.message;
    byId("reset-code-note").hidden = false;
    byId("reset-password-form").reset();
    showAuthPage("reset");
    byId("reset-code-note").hidden = false;
  } catch (error) {
    showAuthError(error.message);
  } finally {
    button.disabled = false;
  }
}

async function resetStudentPassword(form) {
  const details = Object.fromEntries(new FormData(form).entries());
  if (details.password !== details.confirmPassword) {
    showAuthError("The passwords do not match.");
    return;
  }
  const button = form.querySelector('button[type="submit"]');
  byId("login-error").hidden = true;
  button.disabled = true;
  try {
    const result = await api("/api/auth/reset-password", {
      method: "POST",
      body: JSON.stringify({
        email: state.pendingResetEmail,
        code: details.code,
        password: details.password
      })
    });
    state.pendingResetEmail = "";
    byId("student-login-form").reset();
    byId("student-email").value = state.lastResetEmail;
    byId("reset-password-form").reset();
    selectLoginRole("STUDENT");
    notify(result.message);
  } catch (error) {
    showAuthError(error.message);
  } finally {
    button.disabled = false;
  }
}

function notify(message) {
  const toast = byId("toast");
  toast.textContent = message;
  toast.classList.add("show");
  window.clearTimeout(notify.timeout);
  notify.timeout = window.setTimeout(() => toast.classList.remove("show"), 2800);
}

async function loadMenu() {
  byId("menu-status").hidden = false;
  byId("menu-status").textContent = "Fresh picks are on their way...";
  try {
    state.items = await api("/api/items");
    byId("menu-status").hidden = true;
    renderMenu();
    renderAdminItems();
    renderCart();
  } catch (error) {
    byId("menu-status").textContent = error.message;
  }
}

function renderMenu() {
  const filtered = state.items.filter((item) => {
    const matchesCategory = state.category === "All" || item.category === state.category;
    const searchText = `${item.name} ${item.category} ${item.description}`.toLowerCase();
    return matchesCategory && searchText.includes(state.search);
  });
  byId("food-grid").innerHTML = filtered.map((item, index) => `
    <article class="food-card" style="animation-delay:${Math.min(index, 8) * 35}ms">
      <div class="food-image">
        <img src="${escapeHtml(item.image)}" alt="${escapeHtml(item.name)}" loading="lazy">
        <span class="food-category">${escapeHtml(item.category)}</span>
      </div>
      <div class="food-info">
        <h3>${escapeHtml(item.name)}</h3>
        <p>${escapeHtml(item.description)}</p>
        <div class="food-card-bottom">
          <span class="food-price">${money(item.price)} <small>/ serving</small></span>
          ${state.user && state.user.role === "STUDENT"
            ? `<button class="add-button" type="button" data-add="${escapeHtml(item.id)}" aria-label="Add ${escapeHtml(item.name)} to cart">+</button>`
            : ""}
        </div>
      </div>
    </article>
  `).join("");
  byId("menu-empty").hidden = filtered.length !== 0;
}

function renderCart() {
  const validLines = state.cart.filter((line) => state.items.some((item) => item.id === line.id));
  if (validLines.length !== state.cart.length) {
    state.cart = validLines;
    localStorage.setItem("campusCravingsCart", JSON.stringify(state.cart));
  }

  const count = state.cart.reduce((sum, line) => sum + line.quantity, 0);
  const total = state.cart.reduce((sum, line) => {
    const item = state.items.find((candidate) => candidate.id === line.id);
    return sum + (item ? item.price * line.quantity : 0);
  }, 0);
  byId("cart-count").textContent = count;
  byId("drawer-count").textContent = `(${count})`;
  byId("cart-subtotal").textContent = money(total);
  byId("cart-total").textContent = money(total);
  byId("cart-empty").hidden = state.cart.length > 0;
  byId("checkout-form").hidden = state.cart.length === 0 || !state.user || state.user.role !== "STUDENT";
  byId("checkout-identity").textContent = state.user
    ? `Ordering as ${state.user.identity}`
    : "";
  byId("cart-items").innerHTML = state.cart.map((line) => {
    const item = state.items.find((candidate) => candidate.id === line.id);
    if (!item) return "";
    return `
      <div class="cart-line">
        <img src="${escapeHtml(item.image)}" alt="" loading="lazy">
        <div><div class="cart-line-name">${escapeHtml(item.name)}</div><div class="cart-line-price">${money(item.price)} each</div></div>
        <div class="quantity-control">
          <button type="button" data-quantity="${escapeHtml(item.id)}" data-change="-1" aria-label="Remove one ${escapeHtml(item.name)}">−</button>
          <span>${line.quantity}</span>
          <button type="button" data-quantity="${escapeHtml(item.id)}" data-change="1" aria-label="Add one ${escapeHtml(item.name)}">+</button>
        </div>
      </div>
    `;
  }).join("");
}

function updateCart(itemId, change) {
  const line = state.cart.find((candidate) => candidate.id === itemId);
  if (line) {
    if (change > 0 && line.quantity >= 99) {
      notify("You can add up to 99 of each item.");
      return;
    }
    line.quantity += change;
    if (line.quantity < 1) state.cart = state.cart.filter((candidate) => candidate.id !== itemId);
  } else if (change > 0) {
    state.cart.push({ id: itemId, quantity: 1 });
  }
  saveCart();
}

function openCart() {
  byId("cart-drawer").classList.add("open");
  byId("drawer-backdrop").classList.add("open");
  byId("cart-drawer").setAttribute("aria-hidden", "false");
  document.body.classList.add("drawer-open");
  byId("cart-close").focus();
}

function closeCart() {
  byId("cart-drawer").classList.remove("open");
  byId("drawer-backdrop").classList.remove("open");
  byId("cart-drawer").setAttribute("aria-hidden", "true");
  document.body.classList.remove("drawer-open");
}

function formatDate(value) {
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? "Recently" : date.toLocaleString("en-IN", {
    dateStyle: "medium",
    timeStyle: "short"
  });
}

function renderOrders() {
  byId("orders-empty").hidden = state.orders.length > 0;
  byId("orders-list").innerHTML = state.orders.map((order) => {
    const description = order.items.map((item) => `${item.quantity} × ${item.name}`).join(", ");
    const statusClass = String(order.status).toLowerCase();
    return `
      <article class="order-card">
        <div class="order-card-main">
          <strong>${escapeHtml(order.id)} <span class="status-pill ${escapeHtml(statusClass)}">${escapeHtml(order.status)}</span></strong>
          <p>${escapeHtml(description)}</p>
        </div>
        <div class="order-card-side"><span>${formatDate(order.createdAt)}</span><strong>${money(order.total)}</strong></div>
      </article>
    `;
  }).join("");
}

async function loadOrders() {
  state.orders = await api("/api/orders");
  renderOrders();
  renderAdminOrders();
}

function renderAdminItems() {
  byId("admin-items").innerHTML = state.items.map((item) => `
    <div class="admin-item-row">
      <div class="admin-item-name">${escapeHtml(item.name)}<small>${escapeHtml(item.category)} · ${money(item.price)}</small></div>
      <div class="row-actions">
        <button class="small-button" type="button" data-edit="${escapeHtml(item.id)}">Edit</button>
        <button class="small-button delete" type="button" data-delete="${escapeHtml(item.id)}">Delete</button>
      </div>
    </div>
  `).join("") || '<div class="admin-empty">No menu items yet.</div>';
}

function renderAdminOrders() {
  const statuses = ["Received", "Preparing", "Ready", "Collected", "Cancelled"];
  byId("admin-orders").innerHTML = state.orders.map((order) => `
    <div class="admin-order-row">
      <div class="admin-order-info"><strong>${escapeHtml(order.id)} · ${escapeHtml(order.customerName)}</strong><small>${escapeHtml(order.items.map((item) => `${item.quantity} × ${item.name}`).join(", "))}</small></div>
      <div class="admin-order-controls">
        <strong>${money(order.total)}</strong>
        <select data-status="${escapeHtml(order.id)}" aria-label="Update status for ${escapeHtml(order.id)}">
          ${statuses.map((status) => `<option value="${status}" ${status === order.status ? "selected" : ""}>${status}</option>`).join("")}
        </select>
      </div>
    </div>
  `).join("") || '<div class="admin-empty">Orders will appear here after checkout.</div>';
}

function resetItemForm() {
  byId("item-form").reset();
  byId("item-id").value = "";
  byId("item-form-title").textContent = "Add a menu item";
  byId("item-submit").innerHTML = "Add item <span>＋</span>";
  byId("item-cancel").hidden = true;
}

async function refreshAfterOrder() {
  await Promise.all([loadMenu(), loadOrders()]);
}

byId("category-list").addEventListener("click", (event) => {
  const button = event.target.closest("[data-category]");
  if (!button) return;
  state.category = button.dataset.category;
  document.querySelectorAll(".category-chip").forEach((chip) =>
    chip.classList.toggle("active", chip === button)
  );
  renderMenu();
});

byId("menu-search").addEventListener("input", (event) => {
  state.search = event.target.value.trim().toLowerCase();
  renderMenu();
});

byId("food-grid").addEventListener("click", (event) => {
  const button = event.target.closest("[data-add]");
  if (!button) return;
  updateCart(button.dataset.add, 1);
  notify("Added to your cart!");
});

byId("cart-items").addEventListener("click", (event) => {
  const button = event.target.closest("[data-quantity]");
  if (button) updateCart(button.dataset.quantity, Number(button.dataset.change));
});

byId("cart-toggle").addEventListener("click", openCart);
byId("cart-close").addEventListener("click", closeCart);
byId("drawer-backdrop").addEventListener("click", closeCart);
document.addEventListener("keydown", (event) => {
  if (event.key === "Escape") {
    closeCart();
    byId("confirm-overlay").hidden = true;
  }
});

byId("checkout-form").addEventListener("submit", async (event) => {
  event.preventDefault();
  const button = byId("place-order");
  button.disabled = true;
  try {
    const order = await api("/api/orders", {
      method: "POST",
      body: JSON.stringify({
        items: state.cart
      })
    });
    state.cart = [];
    saveCart();
    closeCart();
    byId("confirm-order-id").textContent = order.id;
    byId("confirm-overlay").hidden = false;
    await refreshAfterOrder();
  } catch (error) {
    notify(error.message);
  } finally {
    button.disabled = false;
  }
});

function closeConfirmation() {
  byId("confirm-overlay").hidden = true;
  byId("orders-section").scrollIntoView({ behavior: "smooth" });
}
byId("confirm-close").addEventListener("click", closeConfirmation);
byId("confirm-done").addEventListener("click", closeConfirmation);

byId("orders-link").addEventListener("click", async () => {
  try {
    await loadOrders();
    byId("orders-section").scrollIntoView({ behavior: "smooth" });
  } catch (error) {
    notify(error.message);
  }
  byId("nav-links")?.classList.remove("open");
});

byId("staff-orders-link").addEventListener("click", async () => {
  byId("admin-section").hidden = false;
  byId("admin-section").scrollIntoView({ behavior: "smooth" });
  try {
    await loadOrders();
  } catch (error) {
    notify(error.message);
  }
  byId("nav-links").classList.remove("open");
});

byId("admin-toggle").addEventListener("click", async () => {
  byId("admin-section").hidden = false;
  byId("admin-section").scrollIntoView({ behavior: "smooth" });
  try {
    await Promise.all([loadMenu(), loadOrders()]);
  } catch (error) {
    notify(error.message);
  }
});
byId("admin-close").addEventListener("click", () => {
  byId("admin-section").hidden = true;
  byId("menu").scrollIntoView({ behavior: "smooth" });
});

byId("item-form").addEventListener("submit", async (event) => {
  event.preventDefault();
  const id = byId("item-id").value;
  const item = {
    name: byId("item-name").value.trim(),
    category: byId("item-category").value,
    price: Number(byId("item-price").value),
    description: byId("item-description").value.trim(),
    image: byId("item-image").value.trim()
  };
  try {
    await api(id ? `/api/admin/items/${encodeURIComponent(id)}` : "/api/admin/items", {
      method: id ? "PUT" : "POST",
      body: JSON.stringify(item)
    });
    resetItemForm();
    await loadMenu();
    notify(id ? "Menu item updated." : "Menu item added.");
  } catch (error) {
    notify(error.message);
  }
});

byId("item-cancel").addEventListener("click", resetItemForm);
byId("admin-items").addEventListener("click", async (event) => {
  const editButton = event.target.closest("[data-edit]");
  const deleteButton = event.target.closest("[data-delete]");
  if (editButton) {
    const item = state.items.find((candidate) => candidate.id === editButton.dataset.edit);
    if (!item) return;
    byId("item-id").value = item.id;
    byId("item-name").value = item.name;
    byId("item-category").value = item.category;
    byId("item-price").value = item.price;
    byId("item-description").value = item.description;
    byId("item-image").value = item.image;
    byId("item-form-title").textContent = "Edit menu item";
    byId("item-submit").innerHTML = "Save changes <span>✓</span>";
    byId("item-cancel").hidden = false;
    byId("item-name").focus();
  } else if (deleteButton) {
    const item = state.items.find((candidate) => candidate.id === deleteButton.dataset.delete);
    if (!item || !window.confirm(`Delete "${item.name}" from the menu?`)) return;
    try {
      await api(`/api/admin/items/${encodeURIComponent(item.id)}`, { method: "DELETE" });
      await loadMenu();
      notify("Menu item deleted.");
    } catch (error) {
      notify(error.message);
    }
  }
});

byId("admin-orders").addEventListener("change", async (event) => {
  const select = event.target.closest("[data-status]");
  if (!select) return;
  try {
    await api(`/api/admin/orders/${encodeURIComponent(select.dataset.status)}`, {
      method: "PATCH",
      body: JSON.stringify({ status: select.value })
    });
    await loadOrders();
    notify("Order status updated.");
  } catch (error) {
    notify(error.message);
  }
});

byId("mobile-menu-toggle").addEventListener("click", () => {
  document.querySelector(".nav-links").classList.toggle("open");
});
document.querySelectorAll(".nav-links a").forEach((link) => {
  link.addEventListener("click", () => document.querySelector(".nav-links").classList.remove("open"));
});

document.querySelectorAll("[data-login-role]").forEach((tab) => {
  tab.addEventListener("click", () => selectLoginRole(tab.dataset.loginRole));
});
document.querySelectorAll("[data-auth-page]").forEach((button) => {
  button.addEventListener("click", () => showAuthPage(button.dataset.authPage));
});
byId("create-account-link").addEventListener("click", () => showAuthPage("register"));
byId("forgot-password-link").addEventListener("click", () => {
  byId("forgot-email").value = byId("student-email").value;
  showAuthPage("forgot");
});
byId("student-login-form").addEventListener("submit", (event) => {
  event.preventDefault();
  submitLogin(event.currentTarget, "STUDENT");
});
byId("staff-login-form").addEventListener("submit", (event) => {
  event.preventDefault();
  submitLogin(event.currentTarget, "STAFF");
});
byId("admin-login-form").addEventListener("submit", (event) => {
  event.preventDefault();
  submitLogin(event.currentTarget, "ADMIN");
});
byId("student-register-form").addEventListener("submit", (event) => {
  event.preventDefault();
  submitStudentRegistration(event.currentTarget);
});
byId("forgot-password-form").addEventListener("submit", (event) => {
  event.preventDefault();
  requestStudentPasswordReset(event.currentTarget);
});
byId("reset-password-form").addEventListener("submit", (event) => {
  event.preventDefault();
  resetStudentPassword(event.currentTarget);
});
byId("sign-out").addEventListener("click", async () => {
  try {
    await api("/api/auth/logout", { method: "POST" });
    showLogin();
    selectLoginRole("STUDENT");
    notify("You have signed out.");
  } catch (error) {
    notify(error.message);
  }
});

async function initialize() {
  selectLoginRole("STUDENT");
  await loadMenu();
  try {
    const session = await api("/api/auth/me");
    if (session.authenticated) {
      setUser(session);
      await loadOrders();
    } else {
      showLogin();
    }
  } catch (error) {
    showLogin();
    byId("login-error").textContent = error.message;
    byId("login-error").hidden = false;
  }
}

initialize();
