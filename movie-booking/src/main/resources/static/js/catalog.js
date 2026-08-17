const API_BASE = '/catalog';

// State
let selectedCityId = localStorage.getItem('selectedCityId') || null;
let currentMovieId = null;
let selectedDate = new Date().toISOString().split('T')[0];
let pendingShowBooking = null;

// Initialize
document.addEventListener('DOMContentLoaded', () => {
    loadCities();
    checkAuthState();
});

// Auth Check (matching auth.js token key "accessToken")
function isAuthenticated() {
    return !!localStorage.getItem('accessToken');
}

function checkAuthState() {
    const authNav = document.getElementById('authNav');
    if (isAuthenticated()) {
        authNav.innerHTML = `<button class="btn btn-secondary btn-sm" onclick="window.location.href='/auth.html'">My Account</button>`;
    } else {
        authNav.innerHTML = `<button class="btn btn-primary btn-sm" onclick="redirectToLogin()">Sign In</button>`;
    }
}

// 1. Load Cities
async function loadCities() {
    try {
        const res = await fetch(`${API_BASE}/cities`);
        const result = await res.json();

        if (result.success) {
            const citySelect = document.getElementById('citySelect');
            citySelect.innerHTML = '<option value="" disabled>Select City</option>';

            result.data.forEach(city => {
                const opt = document.createElement('option');
                opt.value = city.id;
                opt.textContent = `${city.name}, ${city.state}`;
                if (String(city.id) === String(selectedCityId)) {
                    opt.selected = true;
                }
                citySelect.appendChild(opt);
            });

            if (!selectedCityId && result.data.length > 0) {
                selectedCityId = result.data[0].id;
                citySelect.value = selectedCityId;
                localStorage.setItem('selectedCityId', selectedCityId);
            }

            if (selectedCityId) {
                const activeCity = result.data.find(c => String(c.id) === String(selectedCityId));
                if (activeCity) {
                    document.getElementById('activeCityLabel').textContent = activeCity.name;
                }
                loadMoviesForCity(selectedCityId);
            }
        }
    } catch (err) {
        console.error('Failed to load cities:', err);
    }
}

function handleCityChange(cityId) {
    selectedCityId = cityId;
    localStorage.setItem('selectedCityId', cityId);
    
    const citySelect = document.getElementById('citySelect');
    document.getElementById('activeCityLabel').textContent = citySelect.options[citySelect.selectedIndex].text.split(',')[0];
    
    navigateToHome();
    loadMoviesForCity(cityId);
}

// 2. Load Movies
async function loadMoviesForCity(cityId) {
    try {
        const res = await fetch(`${API_BASE}/movies?cityId=${cityId}`);
        const result = await res.json();

        const grid = document.getElementById('movieGrid');
        grid.innerHTML = '';

        if (!result.data || result.data.length === 0) {
            grid.innerHTML = `<p style="grid-column: 1/-1; text-align: center; color: var(--text-muted);">No movies currently running in this city.</p>`;
            return;
        }

        result.data.forEach(movie => {
            const card = document.createElement('div');
            card.className = 'movie-card';
            card.onclick = () => loadMovieDetails(movie.id);

            card.innerHTML = `
                <img src="${movie.posterUrl || 'https://via.placeholder.com/300x400?text=No+Poster'}" alt="${movie.title}">
                <div class="movie-card-body">
                    <div class="movie-card-title">${movie.title}</div>
                    <div style="display: flex; gap: 8px; align-items: center; margin-bottom: 8px;">
                        <span class="badge badge-gold">${movie.cbfcRating}</span>
                        <span style="font-size: 0.8rem; color: var(--text-muted);">${movie.durationMinutes}m</span>
                    </div>
                    <div class="tag-group">
                        ${movie.availableFormats.map(f => `<span class="badge badge-pill">${f}</span>`).join('')}
                    </div>
                </div>
            `;
            grid.appendChild(card);
        });
    } catch (err) {
        console.error('Error fetching movies:', err);
    }
}

// 3. Movie Details & Showtimes View
async function loadMovieDetails(movieId) {
    currentMovieId = movieId;
    try {
        const res = await fetch(`${API_BASE}/movies/${movieId}?cityId=${selectedCityId}`);
        const result = await res.json();

        if (!result.success) return;

        const movie = result.data;

        // Populate Details
        document.getElementById('detailTitle').textContent = movie.title;
        document.getElementById('detailCbfc').textContent = movie.cbfcRating;
        document.getElementById('detailRuntime').textContent = `${movie.durationMinutes} min`;
        document.getElementById('detailRelease').textContent = movie.releaseDate;
        document.getElementById('detailDescription').textContent = movie.description;
        document.getElementById('detailCast').textContent = movie.castMembers;
        document.getElementById('detailPoster').src = movie.posterUrl || 'https://via.placeholder.com/200x300';

        if (movie.bannerUrl) {
            document.getElementById('movieBanner').style.backgroundImage = `url('${movie.bannerUrl}')`;
        }

        // Tags
        document.getElementById('detailLanguages').innerHTML = movie.availableLanguages
            .map(l => `<span class="badge badge-pill">${l}</span>`).join('');
        
        document.getElementById('detailFormats').innerHTML = movie.availableFormats
            .map(f => `<span class="badge badge-pill">${f}</span>`).join('');

        if (movie.hasCaptions) {
            document.getElementById('detailCaptions').classList.remove('hidden');
        } else {
            document.getElementById('detailCaptions').classList.add('hidden');
        }

        // Switch Views
        document.getElementById('movieGridView').classList.add('hidden');
        document.getElementById('movieDetailView').classList.remove('hidden');

        // Render Date Bar & Load Shows
        await renderDateBar();

    } catch (err) {
        console.error('Error loading movie details:', err);
    }
}

// Date Selector Bar
// Date strip. Driven by /catalog/movies/{id}/dates so dates that actually have
// confirmed shows are visually distinct from dates that do not, and so we can
// tell the user how far ahead scheduling has been confirmed.
async function renderDateBar() {
    const dateBar = document.getElementById('dateBar');
    dateBar.innerHTML = '<span style="color:var(--text-muted);font-size:0.85rem;">Loading dates...</span>';

    let payload = null;
    try {
        const res = await fetch(`${API_BASE}/movies/${currentMovieId}/dates?cityId=${selectedCityId}&days=7`);
        const result = await res.json();
        payload = result.data;
    } catch (err) {
        console.error('[DATES]', err);
    }

    dateBar.innerHTML = '';
    const entries = (payload && payload.dates) ? payload.dates : [];

    // Default the selection to the first date that actually has shows.
    const firstWithShows = entries.find(e => e.hasShows);
    if (firstWithShows && !entries.some(e => e.date === selectedDate && e.hasShows)) {
        selectedDate = firstWithShows.date;
    }

    entries.forEach(entry => {
        const d = new Date(entry.date + 'T00:00:00');
        const chip = document.createElement('div');

        const classes = ['date-chip'];
        if (entry.date === selectedDate) classes.push('active');
        if (!entry.hasShows) classes.push('no-shows');
        chip.className = classes.join(' ');

        const day = document.createElement('span');
        day.className = 'day';
        day.textContent = d.toLocaleDateString('en-US', { weekday: 'short' });

        const num = document.createElement('span');
        num.className = 'date-num';
        num.textContent = `${d.getDate()} ${d.toLocaleDateString('en-US', { month: 'short' })}`;

        chip.append(day, num);

        if (entry.hasShows) {
            chip.title = `${entry.showCount} show(s)`;
            chip.onclick = () => {
                selectedDate = entry.date;
                document.querySelectorAll('.date-chip').forEach(c => c.classList.remove('active'));
                chip.classList.add('active');
                loadShowtimes();
            };
        } else {
            chip.title = 'No shows scheduled yet';
        }
        dateBar.appendChild(chip);
    });

    const note = document.getElementById('confirmedUntilNote');
    if (note) {
        if (payload && payload.confirmedUntil) {
            const cu = new Date(payload.confirmedUntil + 'T00:00:00');
            note.textContent = `Shows confirmed until ${cu.toLocaleDateString('en-GB', { day: 'numeric', month: 'long' })}.`;
            note.classList.remove('hidden');
        } else {
            note.textContent = 'No confirmed shows in the next 7 days.';
            note.classList.remove('hidden');
        }
    }
    loadShowtimes();
}

// Fetch and Render Shows
async function loadShowtimes() {
    const list = document.getElementById('theatreList');
    list.innerHTML = '<p style="color: var(--text-muted);">Loading showtimes...</p>';

    try {
        const res = await fetch(`${API_BASE}/movies/${currentMovieId}/shows?cityId=${selectedCityId}&date=${selectedDate}`);
        const result = await res.json();

        list.innerHTML = '';

        if (!result.data || result.data.length === 0) {
            list.innerHTML = `<p style="color: var(--text-muted); padding: 16px 0;">No shows available for the selected date.</p>`;
            return;
        }

        result.data.forEach(theatre => {
            const card = document.createElement('div');
            card.className = 'theatre-card';

            // Built with DOM nodes rather than an HTML string so a theatre or
            // screen name containing a quote cannot break the markup.
            const nameEl = document.createElement('div');
            nameEl.className = 'theatre-name';
            nameEl.textContent = theatre.theatreName;

            const addrEl = document.createElement('div');
            addrEl.className = 'theatre-address';
            addrEl.textContent = theatre.address;

            const chipWrap = document.createElement('div');
            chipWrap.className = 'showtime-chips';

            theatre.shows.forEach(show => {
                const showTime = new Date(show.startTime)
                    .toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });

                const total = show.totalSeats || 0;
                const avail = (show.availableSeats === null || show.availableSeats === undefined)
                    ? total : show.availableSeats;
                const ratio = total > 0 ? avail / total : 1;
                const soldOut = show.soldOut === true || avail <= 0;

                const chip = document.createElement('div');
                // Three availability bands, matching how cinema sites signal scarcity.
                chip.className = 'show-chip ' + (soldOut ? 'sold-out'
                    : ratio <= 0.15 ? 'almost-full'
                    : ratio <= 0.5 ? 'filling'
                    : 'plenty');

                const t = document.createElement('div');
                t.className = 'time';
                t.textContent = showTime;

                const sub = document.createElement('div');
                sub.className = 'sub-info';
                sub.textContent = `${show.format}${show.hasCaptions ? ' • CC' : ''}`;

                chip.append(t, sub);

                if (soldOut) {
                    chip.title = 'Tickets are sold out for this show';
                } else {
                    chip.title = `${avail} of ${total} seats available`;
                    chip.onclick = () => handleShowSelection(
                        show.showId, theatre.theatreName, showTime, show.screenName);
                }
                chipWrap.appendChild(chip);
            });

            card.append(nameEl, addrEl, chipWrap);

            list.appendChild(card);
        });

    } catch (err) {
        console.error('Error fetching showtimes:', err);
    }
}

// 4. Booking Auth Gate
function handleShowSelection(showId, theatreName, time, screenName) {
    if (!isAuthenticated()) {
        pendingShowBooking = { showId, theatreName, time, screenName };
        openAuthModal();
    } else {
        initiateBooking(showId, theatreName, time, screenName);
    }
}

// Navigation & Modal Helpers
function navigateToHome() {
    document.getElementById('movieDetailView').classList.add('hidden');
    document.getElementById('movieGridView').classList.remove('hidden');
}

function scrollToShowtimes() {
    document.getElementById('showtimesSection').scrollIntoView({ behavior: 'smooth' });
}

function openAuthModal() {
    document.getElementById('authModal').classList.remove('hidden');
}

function closeAuthModal() {
    document.getElementById('authModal').classList.add('hidden');
    pendingShowBooking = null;
}

function redirectToLogin() {
    window.location.href = '/auth.html';
}

// =========================================
// VIEW 4: SEAT SELECTION & CHECKOUT LOGIC
// =========================================

const BOOKING_API_BASE = '/api/booking';
const MAX_SEATS = 10;

let activeShowContext = null;
let selectedSeats = [];
let activeBookingId = null;
let holdCountdownInterval = null;

// Authenticated helper - booking endpoints always require a valid JWT
async function bookingApiCall(endpoint, method = 'GET', body = null) {
    const token = localStorage.getItem('accessToken');
    const headers = {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${token}`
    };

    const response = await fetch(`${BOOKING_API_BASE}${endpoint}`, {
        method,
        headers,
        body: body ? JSON.stringify(body) : null
    });

    const result = await response.json();

    if (response.status === 401 || response.status === 403) {
        showAlert('Session expired. Please sign in again.', 'error');
        setTimeout(() => window.location.href = '/auth.html', 1500);
        throw new Error('Unauthorized');
    }

    if (!response.ok || result.success === false) {
        showAlert(result.message || 'Something went wrong.', 'error');
        throw new Error(result.message || 'Request failed');
    }

    return result;
}

// 1. Triggered when a user clicks a showtime pill in VIEW 3
let requiredSeatCount = 0;
let seatPriceMap = {};     // seatCode -> price

// The spec requires the ticket quantity to be chosen before the map is shown,
// and the user cannot proceed until they answer.
function handleShowSelection(showId, theatreName, time, screenName) {
    if (!isAuthenticated()) {
        pendingShowBooking = { showId, theatreName, time, screenName };
        openAuthModal();
        return;
    }
    openSeatCountModal({ showId, theatreName, time, screenName });
}

function openSeatCountModal(ctx) {
    activeShowContext = ctx;
    const wrap = document.getElementById('seatCountOptions');
    wrap.innerHTML = '';

    for (let n = 1; n <= MAX_SEATS; n++) {
        const b = document.createElement('button');
        b.className = 'seat-count-btn';
        b.textContent = n;
        b.onclick = () => {
            requiredSeatCount = n;
            document.getElementById('seatCountModal').classList.add('hidden');
            initiateBooking(ctx.showId, ctx.theatreName, ctx.time, ctx.screenName);
        };
        wrap.appendChild(b);
    }
    document.getElementById('seatCountModal').classList.remove('hidden');
}

function closeSeatCountModal() {
    document.getElementById('seatCountModal').classList.add('hidden');
    requiredSeatCount = 0;
}

async function initiateBooking(showId, theatreName, time, screenName) {
    if (!isAuthenticated()) {
        openAuthModal();
        return;
    }
    if (requiredSeatCount < 1) {
        openSeatCountModal({ showId, theatreName, time, screenName });
        return;
    }

    activeShowContext = { showId, theatreName, time, screenName };
    selectedSeats = [];
    activeBookingId = null;
    clearHoldCountdown();
    updateCheckoutBar();

    document.getElementById('bookingMovieTitle').innerText = document.getElementById('detailTitle').innerText;
    document.getElementById('bookingTheatreName').innerText = `${theatreName} - ${screenName}`;
    document.getElementById('bookingShowtime').innerText = time;

    document.getElementById('movieDetailView').classList.add('hidden');
    document.getElementById('seatSelectionView').classList.remove('hidden');

    await fetchAndRenderSeats(showId);
}

// Renders the screen's real grid: tier colours, and pathways left as gaps in the
// exact columns the theatre drew them.
async function fetchAndRenderSeats(showId) {
    const grid = document.getElementById('seatGrid');
    grid.innerHTML = '<div class="loading-spinner">Loading seats...</div>';

    try {
        const res = await bookingApiCall(`/shows/${showId}/seats`, 'GET');
        const map = res.data;

        seatPriceMap = {};
        (map.seats || []).forEach(s => {
            if (s.seatCode && s.price !== null && s.price !== undefined) {
                seatPriceMap[s.seatCode] = Number(s.price);
            }
        });

        renderTierLegend(map.tiers || []);

        const hint = document.getElementById('seatCountHint');
        if (hint) {
            hint.textContent = `Select ${requiredSeatCount} seat${requiredSeatCount > 1 ? 's' : ''}`;
        }

        // Group cells into rows so pathways occupy their real positions.
        const rowsMap = new Map();
        (map.seats || []).forEach(cell => {
            if (!rowsMap.has(cell.rowLabel)) rowsMap.set(cell.rowLabel, []);
            rowsMap.get(cell.rowLabel).push(cell);
        });

        grid.innerHTML = '';
        grid.style.gridTemplateColumns = '';

        [...rowsMap.keys()].sort().forEach(rowLabel => {
            const cells = rowsMap.get(rowLabel).sort((a, b) => a.colIndex - b.colIndex);

            const rowEl = document.createElement('div');
            rowEl.className = 'seat-row';

            const label = document.createElement('span');
            label.className = 'row-label';
            label.textContent = rowLabel;
            rowEl.appendChild(label);

            cells.forEach(cell => {
                if (cell.seatType !== 'SEAT') {
                    const gap = document.createElement('div');
                    gap.className = 'seat pathway';
                    rowEl.appendChild(gap);
                    return;
                }

                const taken = cell.status === 'BOOKED'
                    || (cell.status === 'HELD' && !cell.heldByMe);

                const el = document.createElement('div');
                el.className = `seat ${taken ? 'booked' : 'available'}`;
                el.textContent = cell.seatNumber;
                el.dataset.id = cell.seatCode;

                if (!taken && cell.tierColorHex) {
                    el.style.borderColor = cell.tierColorHex;
                    el.style.boxShadow = `inset 0 -3px 0 ${cell.tierColorHex}`;
                }
                el.title = taken
                    ? `${cell.seatCode} - unavailable`
                    : `${cell.seatCode}${cell.tierName ? ' - ' + cell.tierName : ''} - ₹${cell.price}`;

                if (!taken) {
                    el.onclick = () => toggleSeatSelection(el, cell.seatCode);
                }
                rowEl.appendChild(el);
            });
            grid.appendChild(rowEl);
        });

    } catch (err) {
        grid.innerHTML = '<p class="error-text">Failed to load seat layout.</p>';
    }
}

function renderTierLegend(tiers) {
    const host = document.getElementById('tierLegend');
    if (!host) return;
    host.innerHTML = '';

    tiers.forEach(t => {
        const item = document.createElement('div');
        item.className = 'legend-item';

        const sw = document.createElement('div');
        sw.className = 'seat available';
        if (t.colorHex) {
            sw.style.borderColor = t.colorHex;
            sw.style.boxShadow = `inset 0 -3px 0 ${t.colorHex}`;
        }
        const txt = document.createElement('span');
        txt.textContent = `${t.name} — ₹${t.price}`;

        item.append(sw, txt);
        host.appendChild(item);
    });
}

function toggleSeatSelection(seatDiv, seatId) {
    if (seatDiv.classList.contains('selected')) {
        seatDiv.classList.remove('selected');
        seatDiv.classList.add('available');
        selectedSeats = selectedSeats.filter(id => id !== seatId);
    } else {
        // Capped at the quantity chosen up front, not just the global maximum.
        const cap = requiredSeatCount > 0 ? requiredSeatCount : MAX_SEATS;
        if (selectedSeats.length >= cap) {
            showAlert(`You chose ${cap} ticket${cap > 1 ? 's' : ''}. Deselect a seat to pick a different one.`, 'error');
            return;
        }
        seatDiv.classList.remove('available');
        seatDiv.classList.add('selected');
        selectedSeats.push(seatId);
    }
    updateCheckoutBar();
}

// Total is summed from each seat's own price so a tiered basket is correct.
function updateCheckoutBar() {
    const checkoutBar = document.getElementById('checkoutBar');
    const countDisplay = document.getElementById('selectedSeatCount');
    const priceDisplay = document.getElementById('totalPriceDisplay');
    const payBtn = document.getElementById('payNowBtn');

    if (selectedSeats.length > 0) {
        checkoutBar.classList.remove('hidden');
        const total = selectedSeats.reduce((sum, code) => sum + (seatPriceMap[code] || 0), 0);
        countDisplay.innerText = `${selectedSeats.length} of ${requiredSeatCount || MAX_SEATS} Ticket(s)`;
        priceDisplay.innerText = `₹ ${total}`;

        if (payBtn) {
            const complete = requiredSeatCount === 0 || selectedSeats.length === requiredSeatCount;
            payBtn.disabled = !complete;
            payBtn.textContent = complete
                ? 'Continue'
                : `Select ${requiredSeatCount - selectedSeats.length} more`;
        }
    } else {
        checkoutBar.classList.add('hidden');
    }
}

// Navigation helpers
function goBackToMovieDetail() {
    document.getElementById('seatSelectionView').classList.add('hidden');
    document.getElementById('movieDetailView').classList.remove('hidden');
    selectedSeats = [];
    requiredSeatCount = 0;
    clearHoldCountdown();
    updateCheckoutBar();
}

// 5. Hold the selected seats for 10 minutes (non-refundable policy shown before payment)
async function proceedToPayment() {
    if (selectedSeats.length === 0) return;

    const confirmed = confirm(
        `You're about to hold ${selectedSeats.length} seat(s) for 10 minutes.\n\n` +
        `Tickets are 100% NON-REFUNDABLE once payment is completed.\n\nContinue?`
    );
    if (!confirmed) return;

    try {
        const res = await bookingApiCall('/hold', 'POST', {
            showId: activeShowContext.showId,
            seatCodes: selectedSeats
        });

        const booking = res.data;
        activeBookingId = booking.bookingId;

        showAlert(
            `Seats held: ${booking.seatCodes.join(', ')} | Total ₹${booking.totalAmount} | ` +
            `Transaction ref: ${booking.transactionId}. Complete payment within 10 minutes.`,
            'success'
        );

        startHoldCountdown(booking.holdExpiresAt);
        await fetchAndRenderSeats(activeShowContext.showId);
        selectedSeats = [];
        updateCheckoutBar();
        // Payment module (Razorpay) plugs in here next - for now the hold itself
        // is the end of the booking-core flow.
    } catch (err) {
        // Error already shown by bookingApiCall; refresh seat map since
        // someone may have grabbed a seat in the meantime.
        await fetchAndRenderSeats(activeShowContext.showId);
        selectedSeats = [];
        updateCheckoutBar();
    }
}

function startHoldCountdown(expiresAtIso) {
    clearHoldCountdown();
    const expiresAt = new Date(expiresAtIso).getTime();

    holdCountdownInterval = setInterval(() => {
        const remainingMs = expiresAt - Date.now();
        if (remainingMs <= 0) {
            clearHoldCountdown();
            showAlert('Your seat hold has expired. Please select seats again.', 'error');
            selectedSeats = [];
            activeBookingId = null;
            updateCheckoutBar();
            fetchAndRenderSeats(activeShowContext.showId);
            return;
        }
        const mins = Math.floor(remainingMs / 60000);
        const secs = Math.floor((remainingMs % 60000) / 1000);
        document.getElementById('totalPriceDisplay').innerText =
            `₹ ${selectedSeats.reduce((t, c) => t + (seatPriceMap[c] || 0), 0)} (Hold: ${mins}:${secs.toString().padStart(2, '0')})`;
    }, 1000);
}

function clearHoldCountdown() {
    if (holdCountdownInterval) {
        clearInterval(holdCountdownInterval);
        holdCountdownInterval = null;
    }
}