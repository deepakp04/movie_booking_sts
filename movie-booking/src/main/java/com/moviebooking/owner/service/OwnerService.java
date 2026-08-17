package com.moviebooking.owner.service;

import com.moviebooking.auth.entity.User;
import com.moviebooking.auth.repository.UserRepository;
import com.moviebooking.catalog.model.*;
import com.moviebooking.catalog.repository.*;
import com.moviebooking.catalog.service.ScreenManagementService;
import com.moviebooking.catalog.service.SeatConfigService;
import com.moviebooking.catalog.service.ShowPricingService;
import com.moviebooking.booking.dto.BookingDTOs.BookingResponse;
import com.moviebooking.booking.service.BookingService;
import com.moviebooking.common.exception.BusinessException;
import com.moviebooking.common.exception.ResourceNotFoundException;
import com.moviebooking.owner.dto.OwnerDTOs.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Service
@Transactional
public class OwnerService {

    private final UserRepository userRepository;
    private final TheatreRepository theatreRepository;
    private final ScreenRepository screenRepository;
    private final ShowRepository showRepository;
    private final MovieRepository movieRepository;
    private final ScreenManagementService screenManagement;
    private final SeatConfigService seatConfig;
    private final ShowPricingService showPricing;
    private final ScreenSeatRepository screenSeatRepository;
    private final com.moviebooking.booking.repository.ShowSeatRepository showSeatRepository;
    private final BookingService bookingService;

    public OwnerService(UserRepository userRepository,
                         TheatreRepository theatreRepository,
                         ScreenRepository screenRepository,
                         ShowRepository showRepository,
                         MovieRepository movieRepository,
                         ScreenManagementService screenManagement,
                         SeatConfigService seatConfig,
                         ShowPricingService showPricing,
                         ScreenSeatRepository screenSeatRepository,
                         com.moviebooking.booking.repository.ShowSeatRepository showSeatRepository,
                         BookingService bookingService) {
        this.userRepository = userRepository;
        this.theatreRepository = theatreRepository;
        this.screenRepository = screenRepository;
        this.showRepository = showRepository;
        this.movieRepository = movieRepository;
        this.screenManagement = screenManagement;
        this.seatConfig = seatConfig;
        this.showPricing = showPricing;
        this.screenSeatRepository = screenSeatRepository;
        this.showSeatRepository = showSeatRepository;
        this.bookingService = bookingService;
    }

    // Resolves the theatre owned by whoever is currently authenticated.
    // This is the single choke point that guarantees an owner can never
    // reach another theatre's data - the theatreId is never taken from the client.
    private Theatre currentOwnersTheatre() {
        String email = SecurityContextHolder.getContext().getAuthentication().getName();
        User user = userRepository.findByEmailAndIsDeletedFalse(email)
                .orElseThrow(() -> new ResourceNotFoundException("Authenticated user not found"));

        return theatreRepository.findByOwnerIdAndIsDeletedFalse(user.getId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No theatre is currently assigned to your account. Contact an administrator."));
    }

    public TheatreInfoResponse getMyTheatre() {
        Theatre t = currentOwnersTheatre();
        List<ScreenResponse> screens = t.getScreens() == null ? List.of() :
                t.getScreens().stream()
                        .filter(s -> !Boolean.TRUE.equals(s.getIsDeleted()))
                        .map(this::toScreenResponse)
                        .collect(Collectors.toList());

        return new TheatreInfoResponse(
                t.getId(), t.getName(), t.getAddress(),
                t.getCity() != null ? t.getCity().getId() : null,
                t.getCity() != null ? t.getCity().getName() : "",
                screens
        );
    }

    // --- SCREENS (scoped) ---
    public ScreenResponse createScreen(ScreenRequest req) {
        Theatre t = currentOwnersTheatre();
        Screen screen = new Screen();
        screen.setName(req.name());
        screen.setTotalSeats(req.totalSeats() != null ? req.totalSeats() : 0);
        screen.setTheatre(t);
        Screen saved = screenRepository.save(screen);
        if (t.getScreens() != null) {
            t.getScreens().add(saved);
        }
        return toScreenResponse(saved);
    }

    public List<ScreenResponse> getMyScreens() {
        Theatre t = currentOwnersTheatre();
        return screenRepository.findByTheatreIdAndIsDeletedFalse(t.getId()).stream()
                .map(this::toScreenResponse)
                .collect(Collectors.toList());
    }

    // Screen mutation is delegated to the shared ScreenManagementService with the
    // owner's own theatre id as the restriction, so an owner can never reach a
    // screen belonging to someone else's theatre.
    public ScreenResponse updateScreen(Long screenId, ScreenUpdateRequest req) {
        Theatre t = currentOwnersTheatre();
        return toScreenResponse(screenManagement.rename(screenId, t.getId(), req.name(), req.totalSeats()));
    }

    public void deleteScreen(Long screenId) {
        Theatre t = currentOwnersTheatre();
        screenManagement.softDelete(screenId, t.getId());
    }

    public ScreenLayoutResponse updateScreenLayout(Long screenId, SeatLayoutRequest req) {
        Theatre t = currentOwnersTheatre();
        Screen s = screenManagement.saveLayout(screenId, t.getId(), req.matrix(), req.rows(), req.cols());
        return new ScreenLayoutResponse(s.getId(), s.getName(), s.getTotalSeats(), s.getLayoutJson());
    }

    public ScreenLayoutResponse getScreenLayout(Long screenId) {
        Theatre t = currentOwnersTheatre();
        Screen s = screenManagement.getLayout(screenId, t.getId());
        return new ScreenLayoutResponse(s.getId(), s.getName(), s.getTotalSeats(), s.getLayoutJson());
    }

    private ScreenResponse toScreenResponse(Screen s) {
        return new ScreenResponse(s.getId(), s.getName(), s.getTotalSeats(), s.getLayoutJson());
    }

    // --- MOVIE CATALOG (read-only, for the schedule-show dropdown) ---
    public List<MovieOption> getAvailableMovies() {
        return movieRepository.findByIsDeletedFalseOrderByTitleAsc().stream()
                .map(m -> new MovieOption(m.getId(), m.getTitle(), m.getCbfcRating(), m.getDurationMinutes()))
                .collect(Collectors.toList());
    }

    // --- SHOWS (scoped) ---
    public ShowResponse scheduleShow(ShowRequest req) {
        Theatre t = currentOwnersTheatre();

        Screen screen = screenRepository.findByIdAndIsDeletedFalse(req.screenId())
                .filter(s -> s.getTheatre() != null && s.getTheatre().getId().equals(t.getId()))
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Screen not found in your theatre with ID: " + req.screenId()));

        Movie movie = movieRepository.findByIdAndIsDeletedFalse(req.movieId())
                .orElseThrow(() -> new ResourceNotFoundException("Movie not found with ID: " + req.movieId()));

        Show show = new Show();
        show.setScreen(screen);
        show.setMovie(movie);
        show.setStartTime(req.startTime());
        show.setLanguage(req.language());
        show.setFormat(req.format());
        show.setHasCaptions(req.hasCaptions() != null ? req.hasCaptions() : false);
        show.setBasePrice(req.basePrice());
        if (show.getBasePrice() == null) {
            show.setBasePrice(cheapestTierPrice(req.tierPrices()));
        }
        Show saved = showRepository.save(show);
        showPricing.saveTierPrices(saved, toPricingInputs(req.tierPrices()));
        return toShowResponse(saved);
    }

    public List<ShowResponse> getMyShows(String scope) {
        Theatre t = currentOwnersTheatre();
        return showsForScope(scope).stream()
                .filter(s -> s.getScreen() != null && s.getScreen().getTheatre() != null
                        && s.getScreen().getTheatre().getId().equals(t.getId()))
                .map(this::toShowResponse)
                .collect(Collectors.toList());
    }

    public void cancelShow(Long showId) {
        Theatre t = currentOwnersTheatre();
        Show show = showRepository.findByIdAndIsDeletedFalse(showId)
                .filter(s -> s.getScreen() != null && s.getScreen().getTheatre() != null
                        && s.getScreen().getTheatre().getId().equals(t.getId()))
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Show not found in your theatre with ID: " + showId));
        // Soft delete - a hard delete destroyed the show's paid bookings.
        show.setIsDeleted(true);
        showRepository.save(show);
    }

    private ShowResponse toShowResponse(Show s) {
        return new ShowResponse(
                s.getId(),
                s.getMovie() != null ? s.getMovie().getId() : null,
                s.getMovie() != null ? s.getMovie().getTitle() : null,
                s.getScreen() != null ? s.getScreen().getId() : null,
                s.getScreen() != null ? s.getScreen().getName() : null,
                s.getStartTime(),
                s.getLanguage(),
                s.getFormat(),
                s.getHasCaptions(),
                s.getBasePrice()
        );
    }

    // ================= SEAT TIERS & LAYOUT (Phase 3) =================
    // Identical operations to the admin portal, but every call passes the
    // owner's own theatre id so SeatConfigService rejects any screen that is
    // not theirs.

    private java.math.BigDecimal cheapestTierPrice(List<TierPriceRequest> in) {
        if (in == null || in.isEmpty()) {
            throw new BusinessException(
                    "Enter a ticket price. If this screen has seat tiers, price each tier.");
        }
        return in.stream()
                .filter(p -> p != null && p.price() != null)
                .map(TierPriceRequest::price)
                .min(java.math.BigDecimal::compareTo)
                .orElseThrow(() -> new BusinessException("Enter a ticket price for each seat tier."));
    }

    private List<ShowPricingService.TierPriceInput> toPricingInputs(List<TierPriceRequest> in) {
        if (in == null) {
            return List.of();
        }
        return in.stream()
                .map(p -> new ShowPricingService.TierPriceInput(p.tierId(), p.price()))
                .collect(Collectors.toList());
    }

    public List<SeatTierResponse> listTiers(Long screenId) {
        Theatre t = currentOwnersTheatre();
        return seatConfig.listTiers(screenId, t.getId()).stream()
                .map(this::toTierResponse).collect(Collectors.toList());
    }

    public SeatTierResponse createTier(Long screenId, SeatTierRequest req) {
        Theatre t = currentOwnersTheatre();
        return toTierResponse(seatConfig.createTier(
                screenId, t.getId(), req.name(), req.displayOrder(), req.colorHex()));
    }

    public SeatTierResponse updateTier(Long tierId, SeatTierRequest req) {
        Theatre t = currentOwnersTheatre();
        return toTierResponse(seatConfig.updateTier(
                tierId, t.getId(), req.name(), req.displayOrder(), req.colorHex()));
    }

    public void deleteTier(Long tierId) {
        Theatre t = currentOwnersTheatre();
        seatConfig.deleteTier(tierId, t.getId());
    }

    public ScreenLayoutDetailResponse getLayoutDetail(Long screenId) {
        Theatre t = currentOwnersTheatre();
        return buildLayoutDetail(screenId, t.getId());
    }

    public ScreenLayoutDetailResponse saveLayout(Long screenId, LayoutSaveRequest req) {
        Theatre t = currentOwnersTheatre();
        seatConfig.replaceLayout(screenId, t.getId(), toCellGrid(req.grid()));
        return buildLayoutDetail(screenId, t.getId());
    }

    public ScreenSeatResponse assignSeatTier(Long seatId, SeatTierAssignRequest req) {
        Theatre t = currentOwnersTheatre();
        return toSeatResponse(seatConfig.updateSeatTier(seatId, t.getId(), req.tierId()));
    }

    public List<TierPriceResponse> getShowPrices(Long showId) {
        currentOwnersTheatre();
        return showPricing.listPrices(showId).stream()
                .map(p -> new TierPriceResponse(
                        p.getSeatTier().getId(),
                        p.getSeatTier().getName(),
                        p.getSeatTier().getColorHex(),
                        p.getPrice()))
                .collect(Collectors.toList());
    }

    private List<List<SeatConfigService.SeatCell>> toCellGrid(List<List<SeatCellRequest>> grid) {
        if (grid == null || grid.isEmpty()) {
            throw new BusinessException("Seat layout cannot be empty.");
        }
        List<List<SeatConfigService.SeatCell>> out = new java.util.ArrayList<>();
        for (List<SeatCellRequest> row : grid) {
            List<SeatConfigService.SeatCell> cells = new java.util.ArrayList<>();
            if (row != null) {
                for (SeatCellRequest c : row) {
                    SeatType type = SeatType.SEAT;
                    if (c != null && c.type() != null && !c.type().isBlank()) {
                        try {
                            type = SeatType.valueOf(c.type().trim().toUpperCase());
                        } catch (IllegalArgumentException e) {
                            throw new BusinessException("Unknown seat type: " + c.type());
                        }
                    }
                    cells.add(new SeatConfigService.SeatCell(type, c == null ? null : c.tierId()));
                }
            }
            out.add(cells);
        }
        return out;
    }

    private ScreenLayoutDetailResponse buildLayoutDetail(Long screenId, Long restrictTo) {
        List<SeatTierResponse> tiers = seatConfig.listTiers(screenId, restrictTo).stream()
                .map(this::toTierResponse).collect(Collectors.toList());

        List<ScreenSeat> seats = seatConfig.listSeats(screenId, restrictTo);
        List<ScreenSeatResponse> seatDtos = seats.stream()
                .map(this::toSeatResponse).collect(Collectors.toList());

        int rows = seats.stream().map(ScreenSeat::getRowLabel).distinct().toList().size();
        int cols = seats.stream().mapToInt(ScreenSeat::getColIndex).max().orElse(-1) + 1;

        Screen screen = screenRepository.findByIdAndIsDeletedFalse(screenId)
                .orElseThrow(() -> new ResourceNotFoundException("Screen not found with ID: " + screenId));

        long locked = showSeatRepository.countMaterializedForScreen(screenId);
        boolean editable = (locked == 0);
        String lockReason = editable ? null
                : "Shows on this screen have already opened their seat maps. "
                + "Cancel those shows to change the seating.";

        return new ScreenLayoutDetailResponse(
                screenId, screen.getName(), screen.getTotalSeats(),
                rows, cols, editable, lockReason, tiers, seatDtos);
    }

    private SeatTierResponse toTierResponse(SeatTier t) {
        return new SeatTierResponse(
                t.getId(),
                t.getScreen() != null ? t.getScreen().getId() : null,
                t.getName(), t.getDisplayOrder(), t.getColorHex(),
                screenSeatRepository.countByTierId(t.getId()));
    }

    private ScreenSeatResponse toSeatResponse(ScreenSeat s) {
        SeatTier t = s.getSeatTier();
        return new ScreenSeatResponse(
                s.getId(), s.getRowLabel(), s.getColIndex(), s.getSeatNumber(),
                s.getSeatCode(), s.getSeatType().name(),
                t != null ? t.getId() : null,
                t != null ? t.getName() : null,
                t != null ? t.getColorHex() : null);
    }

    // Scope filter for the show list. Previously every show ever scheduled was
    // returned, so last month's completed screenings sat alongside upcoming ones.
    // "upcoming" starts at the beginning of today so a show earlier today is
    // still visible to staff rather than vanishing at its start time.
    private List<Show> showsForScope(String scope) {
        String s = (scope == null || scope.isBlank()) ? "upcoming" : scope.trim().toLowerCase();
        LocalDateTime startOfToday = java.time.LocalDate.now().atStartOfDay();

        switch (s) {
            case "past":
                return showRepository.findByIsDeletedFalseAndStartTimeLessThanOrderByStartTimeDesc(startOfToday);
            case "all":
                return showRepository.findByIsDeletedFalseOrderByStartTimeDesc();
            case "upcoming":
            default:
                return showRepository
                        .findByIsDeletedFalseAndStartTimeGreaterThanEqualOrderByStartTimeAsc(startOfToday);
        }
    }

    // ===== Booking logs (Owner-scoped) =====
    public List<BookingResponse> getMyTheatreBookings() {
        Theatre theatre = currentOwnersTheatre();
        return bookingService.getBookingsByTheatre(theatre.getId());
    }

    public List<BookingResponse> getShowBookings(Long showId) {
        Theatre theatre = currentOwnersTheatre();
        // Verify the show belongs to this theatre
        Show show = showRepository.findByIdAndIsDeletedFalse(showId)
                .orElseThrow(() -> new ResourceNotFoundException("Show not found"));
        if (!show.getScreen().getTheatre().getId().equals(theatre.getId())) {
            throw new ResourceNotFoundException("Show not found");
        }
        return bookingService.getBookingsByShow(showId);
    }
}
